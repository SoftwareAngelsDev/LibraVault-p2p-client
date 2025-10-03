package p2p.domain.wtfs

import p2p.utils.prettyPrint

abstract class PeerKey(
    private val key: ByteArray,
) {
    companion object {
        const val SIZE_BYTES = 512 // RSA 4096 key size
    }

    init {
        require(key.size == SIZE_BYTES) { "Key must be $SIZE_BYTES bytes long, not ${key.size}" }
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

    override fun toString() = key.prettyPrint()
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
