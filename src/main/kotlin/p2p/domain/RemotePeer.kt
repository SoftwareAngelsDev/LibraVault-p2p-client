package p2p.domain

typealias UserPublicKey = ByteArray // 4096 bits
typealias UserSignature = ByteArray // 4096 bits
typealias RemotePeerPublicKey = UserPublicKey
typealias RemotePeerId = RemotePeerPublicKey

class RemotePeer(
    val publicKey: RemotePeerPublicKey,
) {
    val id = publicKey

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
