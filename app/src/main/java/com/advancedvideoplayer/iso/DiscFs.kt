package com.advancedvideoplayer.iso

import java.io.ByteArrayOutputStream

/** A byte range inside the disc image. */
data class Extent(val offset: Long, val length: Long)

class FsNode(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val extents: List<Extent>,
    val inline: ByteArray? = null
)

interface DiscFs {
    fun root(): FsNode
    fun list(dir: FsNode): List<FsNode>
    fun read(node: FsNode, limit: Int): ByteArray
}

internal fun IsoSource.readNode(node: FsNode, limit: Int): ByteArray {
    node.inline?.let { return it.copyOf(minOf(it.size, limit)) }
    val out = ByteArrayOutputStream()
    var remaining = minOf(node.size, limit.toLong())
    for (e in node.extents) {
        if (remaining <= 0) break
        val n = minOf(e.length, remaining).toInt()
        out.write(read(e.offset, n))
        remaining -= n
    }
    return out.toByteArray()
}
