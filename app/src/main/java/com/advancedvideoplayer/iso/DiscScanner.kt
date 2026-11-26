package com.advancedvideoplayer.iso

import java.util.Locale

/** One playable thing found on a disc: a Blu-ray clip or a DVD title set. */
class DiscTitle(
    val label: String,
    val fileName: String,
    val extents: List<Extent>,
    val bytes: Long
)

class DiscScan(val kind: String, val titles: List<DiscTitle>)

object DiscScanner {

    private const val MIN_TITLE_BYTES = 50L * 1024 * 1024
    private const val MAX_TITLES = 20

    /** Returns null when the image is not a readable Blu-ray / DVD-Video disc. */
    fun scan(io: IsoSource): DiscScan? {
        val fs: DiscFs = Udf.tryOpen(io) ?: Iso9660.tryOpen(io) ?: return null
        val root = fs.root()
        val top = fs.list(root)

        top.firstOrNull { it.isDir && it.name.equals("BDMV", true) }?.let { bdmv ->
            val stream = fs.list(bdmv).firstOrNull { it.isDir && it.name.equals("STREAM", true) }
            if (stream != null) {
                val clips = fs.list(stream)
                    .filter { !it.isDir && it.name.endsWith(".m2ts", true) && it.size > 0 }
                    .sortedByDescending { it.size }
                val big = clips.filter { it.size >= MIN_TITLE_BYTES }.ifEmpty { clips }
                val titles = big.take(MAX_TITLES).mapIndexed { i, n ->
                    DiscTitle(
                        label = (if (i == 0) "Main movie (likely)  " else "") + n.name + "  ·  " + size(n.size),
                        fileName = n.name,
                        extents = n.extents,
                        bytes = n.size
                    )
                }
                if (titles.isNotEmpty()) return DiscScan("Blu-ray", titles)
            }
        }

        top.firstOrNull { it.isDir && it.name.equals("VIDEO_TS", true) }?.let { vts ->
            val vob = Regex("VTS_(\\d\\d)_(\\d+)\\.VOB", RegexOption.IGNORE_CASE)
            val sets = sortedMapOf<Int, MutableList<Pair<Int, FsNode>>>()
            for (n in fs.list(vts)) {
                if (n.isDir) continue
                val m = vob.matchEntire(n.name) ?: continue
                val part = m.groupValues[2].toInt()
                if (part == 0) continue // _0 is the menu
                sets.getOrPut(m.groupValues[1].toInt()) { ArrayList() }.add(part to n)
            }
            val titles = sets.map { (setNo, parts) ->
                val ordered = parts.sortedBy { it.first }.map { it.second }
                val bytes = ordered.sumOf { it.size }
                val tag = String.format(Locale.US, "%02d", setNo)
                Triple(setNo, bytes, DiscTitle(
                    label = "Title set $tag  ·  " + size(bytes),
                    fileName = "VTS_$tag.vob",
                    extents = ordered.flatMap { it.extents },
                    bytes = bytes
                ))
            }.filter { it.second >= MIN_TITLE_BYTES }
                .sortedByDescending { it.second }
                .map { it.third }
                .take(MAX_TITLES)
            if (titles.isNotEmpty()) {
                val first = titles.first()
                val renamed = listOf(
                    DiscTitle("Main movie (likely)  " + first.label, first.fileName, first.extents, first.bytes)
                ) + titles.drop(1)
                return DiscScan("DVD", renamed)
            }
        }
        return null
    }

    private fun size(b: Long): String =
        if (b >= 1L shl 30) String.format(Locale.US, "%.1f GB", b / 1073741824.0)
        else String.format(Locale.US, "%.0f MB", b / 1048576.0)
}
