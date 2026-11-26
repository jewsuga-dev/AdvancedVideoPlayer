package com.advancedvideoplayer.iso

import android.net.Uri
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands "a movie inside a disc image" to the player as a normal-looking URI:
 * `isoplay://disc/<id>/<name>`. The player's data source resolves the id back to the byte ranges
 * of the ISO file (see [IsoDataSource]).
 */
object IsoPlayback {
    const val SCHEME = "isoplay"

    class Entry(val source: IsoSource, val extents: List<Extent>) {
        val total: Long = extents.sumOf { it.length }
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(source: IsoSource, extents: List<Extent>, fileName: String): Uri {
        val id = UUID.randomUUID().toString().take(8)
        entries[id] = Entry(source, extents)
        return Uri.Builder().scheme(SCHEME).authority("disc").appendPath(id).appendPath(fileName).build()
    }

    fun isIsoUri(uri: Uri?): Boolean = uri?.scheme == SCHEME

    fun find(uri: Uri): Entry? = uri.pathSegments.firstOrNull()?.let { entries[it] }

    /** Forgets every title that reads from [source] (call before closing it). */
    fun release(source: IsoSource) {
        entries.entries.removeIf { it.value.source === source }
    }
}
