package com.advancedvideoplayer.iso

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random-access reader over a disc image (file:// path or content:// document). */
class IsoSource private constructor(val channel: FileChannel, private val owner: Closeable) : Closeable {

    fun read(pos: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = channel.read(buf, p)
            if (n < 0) break
            p += n
        }
        return buf.array().copyOf(buf.position())
    }

    override fun close() {
        try {
            owner.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        fun open(context: Context, uri: Uri): IsoSource {
            if (uri.scheme == "file") {
                val path = uri.path ?: throw IOException("Bad file path")
                val stream = FileInputStream(File(path))
                return IsoSource(stream.channel, stream)
            }
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open file")
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            return IsoSource(stream.channel, stream)
        }
    }
}
