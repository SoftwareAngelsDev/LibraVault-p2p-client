package p2p.domain

typealias RemotePeerPublicKey = ByteArray

class RemotePeer(
    val publicKey: RemotePeerPublicKey,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RemotePeer

        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        return publicKey.contentHashCode()
    }
}
