package p2p.utils

import java.nio.ByteBuffer

fun Long.toByteArray(): ByteArray {
    return ByteBuffer.allocate(Long.SIZE_BYTES).putLong(this).array()
}

fun ByteArray.toLong(): Long {
    if (this.size != Long.SIZE_BYTES) {
        throw IllegalArgumentException("Byte array must be ${Long.SIZE_BYTES} bytes")
    }

    return ByteBuffer.wrap(this).long
}
