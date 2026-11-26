package com.advancedvideoplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Lets Media3 play Blu-ray `.m2ts` / `.mts` files.
 *
 * Those files use 192-byte packets (a 4-byte timestamp header + a normal 188-byte MPEG-TS packet),
 * which Media3's TS extractor rejects ("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"). This wrapper
 * detects that layout and strips the 4-byte headers on the fly, so the extractor sees a plain
 * 188-byte TS stream. Seeking is translated between the two layouts. Any other file is passed
 * through untouched.
 */
@OptIn(UnstableApi::class)
class BdavDataSource(private val upstream: DataSource) : DataSource {

    private var bdav = false
    private val packet = ByteArray(BDAV_PACKET)
    private var pktPos = 0
    private var pktLen = 0
    private var firstSkip = 0

    private var sniffedUri: Uri? = null
    private var sniffedResult = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        pktPos = 0
        pktLen = 0
        firstSkip = 0
        bdav = isCandidate(dataSpec.uri) && isBdav(dataSpec)
        if (!bdav) return upstream.open(dataSpec)

        val unset = C.LENGTH_UNSET.toLong()
        val startPacket = dataSpec.position / TS_PACKET
        val skipInPacket = (dataSpec.position % TS_PACKET).toInt()
        val physStart = startPacket * BDAV_PACKET
        val physLength = if (dataSpec.length == unset) {
            unset
        } else {
            val endPacket = (dataSpec.position + dataSpec.length + TS_PACKET - 1) / TS_PACKET
            endPacket * BDAV_PACKET - physStart
        }
        val physRemaining = upstream.open(
            dataSpec.buildUpon().setPosition(physStart).setLength(physLength).build()
        )
        firstSkip = skipInPacket

        if (physRemaining == unset) return dataSpec.length
        val fullPackets = physRemaining / BDAV_PACKET
        val tail = (physRemaining % BDAV_PACKET - HEADER).coerceAtLeast(0)
        val logicalRemaining = fullPackets * TS_PACKET + tail - skipInPacket
        val result = logicalRemaining.coerceAtLeast(0)
        return if (dataSpec.length != unset) minOf(result, dataSpec.length) else result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!bdav) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (pktPos >= pktLen) {
            if (!fillPacket()) return C.RESULT_END_OF_INPUT
        }
        val n = minOf(length, pktLen - pktPos)
        System.arraycopy(packet, pktPos, buffer, offset, n)
        pktPos += n
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        bdav = false
        upstream.close()
    }

    /** Reads the next 192-byte packet and positions [pktPos] after its 4-byte header. */
    private fun fillPacket(): Boolean {
        var got = 0
        while (got < BDAV_PACKET) {
            val r = upstream.read(packet, got, BDAV_PACKET - got)
            if (r == C.RESULT_END_OF_INPUT) break
            got += r
        }
        if (got <= HEADER) return false
        pktLen = got
        pktPos = HEADER + firstSkip
        firstSkip = 0
        return true
    }

    private fun isCandidate(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return true
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        return name.endsWith(".m2ts") || name.endsWith(".mts")
    }

    /** Sync byte 0x47 at offsets 4, 196 and 388 (and not at 0 / 188) = 192-byte BDAV packets. */
    private fun isBdav(spec: DataSpec): Boolean {
        if (spec.uri == sniffedUri) return sniffedResult
        val probe = ByteArray(2 * BDAV_PACKET + HEADER + 1)
        var result = false
        try {
            upstream.open(
                spec.buildUpon().setPosition(0).setLength(probe.size.toLong()).build()
            )
            var got = 0
            while (got < probe.size) {
                val r = upstream.read(probe, got, probe.size - got)
                if (r == C.RESULT_END_OF_INPUT) break
                got += r
            }
            val sync = 0x47.toByte()
            result = got >= probe.size &&
                probe[HEADER] == sync &&
                probe[HEADER + BDAV_PACKET] == sync &&
                probe[HEADER + 2 * BDAV_PACKET] == sync &&
                !(probe[0] == sync && probe[TS_PACKET] == sync)
        } catch (_: IOException) {
            result = false
        } finally {
            try {
                upstream.close()
            } catch (_: IOException) {
            }
        }
        sniffedUri = spec.uri
        sniffedResult = result
        return result
    }

    private companion object {
        const val TS_PACKET = 188
        const val HEADER = 4
        const val BDAV_PACKET = TS_PACKET + HEADER
    }
}
