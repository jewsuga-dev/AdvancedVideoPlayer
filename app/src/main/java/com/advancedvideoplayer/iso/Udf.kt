package com.advancedvideoplayer.iso

import java.io.IOException

/**
 * Minimal read-only UDF reader (1.02 – 2.60, including the Metadata Partition that Blu-ray discs use).
 * Only what is needed to walk directories and find a file's byte ranges.
 */
internal class Udf private constructor(private val io: IsoSource) : DiscFs {

    private class PartMap(val metadata: Boolean, val partNumber: Int, val metaFileLoc: Long)
    private class Ad(val mapIdx: Int, val lbn: Long, val len: Long)
    private class Entry(val isDir: Boolean, val info: Long, val ads: List<Ad>, val inline: ByteArray?)

    private val maps = ArrayList<PartMap>()
    private val partStarts = HashMap<Int, Long>()
    private val metaExtents = ArrayList<LongArray>() // [first logical block, physical sector, block count]
    private var physIdx = 0
    private var fsdLoc = -1L
    private var fsdRef = 0
    private var rootCache: FsNode? = null

    companion object {
        private const val S = 2048
        fun tryOpen(io: IsoSource): Udf? = try {
            Udf(io).takeIf { it.init() }
        } catch (e: Exception) {
            null
        }
    }

    private fun init(): Boolean {
        val avdp = io.read(256L * S, S)
        if (avdp.size < S || avdp.u16(0) != 2) return false
        val vdsLen = avdp.u32(16)
        val vdsLoc = avdp.u32(20)
        val count = (vdsLen / S).toInt().coerceIn(1, 64)
        loop@ for (i in 0 until count) {
            val d = io.read((vdsLoc + i) * S, S)
            if (d.size < S) break
            when (d.u16(0)) {
                5 -> partStarts[d.u16(22)] = d.u32(188)
                6 -> readLvd(d)
                8 -> break@loop
            }
        }
        if (maps.isEmpty() || fsdLoc < 0) return false
        physIdx = maps.indexOfFirst { !it.metadata }
        if (physIdx < 0) return false

        val meta = maps.firstOrNull { it.metadata }
        if (meta != null) {
            val start = partStarts[meta.partNumber] ?: return false
            val fe = io.read((start + meta.metaFileLoc) * S, S)
            val e = parseEntry(fe, physIdx) ?: return false
            var logical = 0L
            for (ad in e.ads) {
                val base = partStarts[maps[ad.mapIdx].partNumber] ?: return false
                val blocks = ad.len / S
                metaExtents.add(longArrayOf(logical, base + ad.lbn, blocks))
                logical += blocks
            }
        }
        return true
    }

    private fun readLvd(d: ByteArray) {
        fsdLoc = d.u32(252)
        fsdRef = d.u16(256)
        val n = d.u32(268).toInt()
        var p = 440
        for (i in 0 until n) {
            if (p + 2 > d.size) break
            val type = d.u8(p)
            val len = d.u8(p + 1)
            if (len == 0 || p + len > d.size) break
            if (type == 1) {
                maps.add(PartMap(false, d.u16(p + 4), 0L))
            } else if (type == 2) {
                val id = String(d, p + 5, 23, Charsets.ISO_8859_1)
                if (id.contains("Metadata")) maps.add(PartMap(true, d.u16(p + 38), d.u32(p + 40)))
                else maps.add(PartMap(false, d.u16(p + 38), 0L))
            }
            p += len
        }
    }

    /** Byte offset in the image of logical block [lbn] of partition map [mapIdx]. */
    private fun byteAddr(mapIdx: Int, lbn: Long): Long {
        val m = maps[mapIdx]
        if (!m.metadata) return ((partStarts[m.partNumber] ?: throw IOException("No partition")) + lbn) * S
        for (e in metaExtents) {
            if (lbn >= e[0] && lbn < e[0] + e[2]) return (e[1] + (lbn - e[0])) * S
        }
        throw IOException("Metadata block out of range")
    }

    private fun parseAds(buf: ByteArray, start: Int, len: Int, allocType: Int, ownerMap: Int, out: MutableList<Ad>) {
        val step = if (allocType == 1) 16 else 8
        var p = start
        val end = minOf(start + len, buf.size)
        while (p + step <= end) {
            val raw = buf.u32(p)
            val type = (raw ushr 30).toInt()
            val l = raw and 0x3FFFFFFFL
            if (l == 0L) break
            val lbn = buf.u32(p + 4)
            val map = if (allocType == 1) buf.u16(p + 8) else ownerMap
            when (type) {
                0 -> out.add(Ad(map, lbn, l))
                3 -> {
                    val cont = io.read(byteAddr(map, lbn), minOf(l, (S * 8).toLong()).toInt())
                    if (cont.size >= 24) parseAds(cont, 24, cont.u32(20).toInt(), allocType, ownerMap, out)
                }
                else -> throw IOException("Sparse extents are not supported")
            }
            p += step
        }
    }

    private fun parseEntry(b: ByteArray, ownerMap: Int): Entry? {
        if (b.size < 176) return null
        val tag = b.u16(0)
        if (tag != 261 && tag != 266) return null
        val isDir = b.u8(27) == 4
        val allocType = b.u16(34) and 7
        val info = b.u64(56)
        val lea: Int
        val lad: Int
        val base: Int
        if (tag == 261) {
            lea = b.u32(168).toInt(); lad = b.u32(172).toInt(); base = 176
        } else {
            if (b.size < 216) return null
            lea = b.u32(208).toInt(); lad = b.u32(212).toInt(); base = 216
        }
        val start = base + lea
        if (start > b.size) return null
        val len = minOf(lad, b.size - start)
        if (allocType == 3) {
            val n = minOf(len.toLong(), info).toInt()
            return Entry(isDir, info, emptyList(), b.copyOfRange(start, start + n))
        }
        if (allocType > 1) return null
        val ads = ArrayList<Ad>()
        parseAds(b, start, len, allocType, ownerMap, ads)
        return Entry(isDir, info, ads, null)
    }

    private fun loadEntry(mapIdx: Int, lbn: Long): Entry? {
        val b = io.read(byteAddr(mapIdx, lbn), S)
        if (b.size < 176) return null
        val isDir = b.u8(27) == 4
        // Directories live in the partition their entry is in (metadata); file data is always on the physical one.
        return parseEntry(b, if (isDir) mapIdx else physIdx)
    }

    private fun toNode(name: String, e: Entry): FsNode {
        var left = e.info
        val ext = ArrayList<Extent>()
        for (ad in e.ads) {
            if (left <= 0) break
            val l = minOf(ad.len, left)
            ext.add(Extent(byteAddr(ad.mapIdx, ad.lbn), l))
            left -= l
        }
        return FsNode(name, e.isDir, e.info, ext, e.inline)
    }

    override fun root(): FsNode {
        rootCache?.let { return it }
        val fsd = io.read(byteAddr(fsdRef, fsdLoc), S)
        if (fsd.size < S || fsd.u16(0) != 256) throw IOException("No file set descriptor")
        val e = loadEntry(fsd.u16(408), fsd.u32(404)) ?: throw IOException("No root directory")
        return toNode("", e).also { rootCache = it }
    }

    override fun read(node: FsNode, limit: Int) = io.readNode(node, limit)

    override fun list(dir: FsNode): List<FsNode> {
        val data = read(dir, 4 * 1024 * 1024)
        val out = ArrayList<FsNode>()
        var p = 0
        while (p + 38 <= data.size) {
            if (data.u16(p) != 257) break
            val chars = data.u8(p + 18)
            val lfi = data.u8(p + 19)
            val lbn = data.u32(p + 24)
            val ref = data.u16(p + 28)
            val liu = data.u16(p + 36)
            if (p + 38 + liu + lfi > data.size) break
            val total = (38 + liu + lfi + 3) and 3.inv()
            if ((chars and 0x0C) == 0 && lfi > 0 && ref < maps.size) {
                val name = decodeName(data, p + 38 + liu, lfi)
                try {
                    loadEntry(ref, lbn)?.let { out.add(toNode(name, it)) }
                } catch (_: Exception) {
                }
            }
            p += total
        }
        return out
    }

    private fun decodeName(d: ByteArray, off: Int, len: Int): String {
        if (len < 2) return ""
        return if (d.u8(off) == 16) String(d, off + 1, len - 1, Charsets.UTF_16BE)
        else String(d, off + 1, len - 1, Charsets.ISO_8859_1)
    }
}
