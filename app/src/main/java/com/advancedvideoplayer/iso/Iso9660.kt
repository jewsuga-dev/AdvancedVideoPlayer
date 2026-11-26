package com.advancedvideoplayer.iso

/** Plain ISO 9660 (used as a fallback for DVD images that have no UDF). */
internal class Iso9660 private constructor(private val io: IsoSource) : DiscFs {

    private var rootLba = 0L
    private var rootSize = 0L

    companion object {
        private const val S = 2048
        fun tryOpen(io: IsoSource): Iso9660? = try {
            Iso9660(io).takeIf { it.init() }
        } catch (e: Exception) {
            null
        }
    }

    private fun init(): Boolean {
        for (sector in 16L..32L) {
            val d = io.read(sector * S, S)
            if (d.size < S) return false
            if (String(d, 1, 5, Charsets.ISO_8859_1) != "CD001") return false
            val type = d.u8(0)
            if (type == 1) {
                rootLba = d.u32(156 + 2)
                rootSize = d.u32(156 + 10)
                return true
            }
            if (type == 255) return false
        }
        return false
    }

    override fun root() = FsNode("", true, rootSize, listOf(Extent(rootLba * S, rootSize)))

    override fun read(node: FsNode, limit: Int) = io.readNode(node, limit)

    override fun list(dir: FsNode): List<FsNode> {
        val data = read(dir, 4 * 1024 * 1024)
        val out = ArrayList<FsNode>()
        var p = 0
        while (p < data.size) {
            val len = data.u8(p)
            if (len == 0) {
                p = (p / S + 1) * S
                continue
            }
            if (p + 33 > data.size) break
            val flags = data.u8(p + 25)
            val nameLen = data.u8(p + 32)
            if (p + 33 + nameLen > data.size) break
            val special = nameLen == 1 && (data.u8(p + 33) == 0 || data.u8(p + 33) == 1)
            if (!special) {
                val name = String(data, p + 33, nameLen, Charsets.ISO_8859_1).substringBefore(';').trimEnd('.')
                val lba = data.u32(p + 2)
                val size = data.u32(p + 10)
                out.add(FsNode(name, (flags and 2) != 0, size, listOf(Extent(lba * S, size))))
            }
            p += len
        }
        return out
    }
}
