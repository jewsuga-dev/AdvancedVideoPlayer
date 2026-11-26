package com.advancedvideoplayer.iso

internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
internal fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
internal fun ByteArray.u32(o: Int): Long = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
internal fun ByteArray.u64(o: Int): Long = u32(o) or (u32(o + 4) shl 32)
internal fun ByteArray.be16(o: Int): Int = (u8(o) shl 8) or u8(o + 1)
internal fun ByteArray.be32(o: Int): Long = (be16(o).toLong() shl 16) or be16(o + 2).toLong()
