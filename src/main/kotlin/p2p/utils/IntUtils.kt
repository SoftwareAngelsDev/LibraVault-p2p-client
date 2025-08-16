package p2p.utils

import java.nio.ByteBuffer

fun Int.toByteArray(): ByteArray {
    return ByteBuffer.allocate(Int.SIZE_BYTES).putInt(this).array()
}

fun ByteArray.toInt(): Int {
    if (this.size != Int.SIZE_BYTES) {
        throw IllegalArgumentException("Byte array must be ${Int.SIZE_BYTES} bytes")
    }

    return ByteBuffer.wrap(this).int
}
