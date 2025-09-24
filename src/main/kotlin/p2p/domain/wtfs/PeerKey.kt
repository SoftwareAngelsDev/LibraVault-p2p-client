package p2p.domain.wtfs

import java.util.*

abstract class PeerKey(
    private val key: ByteArray,
) {
    companion object {
        const val SIZE_BYTES = 512
    }

    init {
        require(key.size == SIZE_BYTES) { "Key must be $SIZE_BYTES bytes long" }
    }

    fun toByteArray(): ByteArray {
        return key
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PeerKey

        return key.contentEquals(other.key)
    }

    override fun hashCode(): Int {
        return key.contentHashCode()
    }

    override fun toString(): String {
        Base64.getEncoder().encodeToString(key).let {
            val firstChars = it.take(5)
            val lastChars = it.takeLast(5)
            return "$firstChars...$lastChars"
        }
    }
}

class PeerPublicKey(key: ByteArray) : PeerKey(key) {
    companion object {
        const val SIZE_BYTES = PeerKey.SIZE_BYTES
    }
}

class PeerPrivateKey(key: ByteArray) : PeerKey(key) {
    companion object {
        const val SIZE_BYTES = PeerKey.SIZE_BYTES
    }
}
