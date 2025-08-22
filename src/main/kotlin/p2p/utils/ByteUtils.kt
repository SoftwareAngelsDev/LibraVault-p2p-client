package p2p.utils

import java.nio.ByteBuffer

fun convertKBToBytes(kilobytes: Long): Long {
    return kilobytes * 1024
}

fun convertMBToBytes(megabytes: Long): Long {
    return megabytes * 1024 * 1024
}

fun convertGBToBytes(gigabytes: Long): Long {
    return convertMBToBytes(gigabytes * 1024)
}

fun mergeByteArrays(vararg byteArrays: ByteArray): ByteArray {
    return ByteBuffer
        .allocate(byteArrays.sumOf { it.size })
        .apply {
            byteArrays.forEach { put(it) }
        }
        .array()
}

fun Byte.toByteArray(): ByteArray {
    return ByteBuffer.allocate(1).put(this).array()
}

fun UByte.toByteArray(): ByteArray {
    return this.toByte().toByteArray()
}