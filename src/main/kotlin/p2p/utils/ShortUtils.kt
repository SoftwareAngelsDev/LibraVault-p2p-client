package p2p.utils

import java.nio.ByteBuffer

fun Short.toByteArray(): ByteArray {
    return ByteBuffer.allocate(Short.SIZE_BYTES).putShort(this).array()
}

fun ByteArray.toShort(): Short {
    if (this.size != Short.SIZE_BYTES) {
        throw IllegalArgumentException("Byte array must be ${Short.SIZE_BYTES} bytes")
    }

    return ByteBuffer.wrap(this).short
}
