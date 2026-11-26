package com.advancedvideoplayer.iso

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.nio.ByteBuffer

/** Reads one disc title (a list of byte ranges inside the ISO file) as a single continuous stream. */
@OptIn(UnstableApi::class)
class IsoDataSource : BaseDataSource(/* isNetwork = */ false) {

    private var uri: Uri? = null
    private var entry: IsoPlayback.Entry? = null
    private var extentIdx = 0
    private var offsetInExtent = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val e = IsoPlayback.find(dataSpec.uri) ?: throw IOException("Disc image is no longer open")
        if (dataSpec.position > e.total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        entry = e
        uri = dataSpec.uri

        var acc = 0L
        extentIdx = e.extents.size
        offsetInExtent = 0L
        for (i in e.extents.indices) {
            val len = e.extents[i].length
            if (dataSpec.position < acc + len) {
                extentIdx = i
                offsetInExtent = dataSpec.position - acc
                break
            }
            acc += len
        }
        val available = e.total - dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, available) else available
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val e = entry ?: return C.RESULT_END_OF_INPUT
        if (remaining <= 0L || extentIdx >= e.extents.size) return C.RESULT_END_OF_INPUT

        val ext = e.extents[extentIdx]
        val toRead = minOf(length.toLong(), remaining, ext.length - offsetInExtent).toInt()
        val n = e.source.channel.read(ByteBuffer.wrap(buffer, offset, toRead), ext.offset + offsetInExtent)
        if (n <= 0) throw IOException("Unexpected end of disc image")

        offsetInExtent += n
        remaining -= n
        if (offsetInExtent >= ext.length) {
            extentIdx++
            offsetInExtent = 0L
        }
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        entry = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** Sends `isoplay://` URIs to [IsoDataSource] and everything else to the normal data source. */
@OptIn(UnstableApi::class)
class IsoRoutingDataSource(private val base: DataSource) : DataSource {

    private val iso = IsoDataSource()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        base.addTransferListener(transferListener)
        iso.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (IsoPlayback.isIsoUri(dataSpec.uri)) iso else base
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
