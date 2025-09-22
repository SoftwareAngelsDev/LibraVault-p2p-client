package p2p.domain

typealias PeerPublicKey = ByteArray // 4096 bits = 512 bytes
typealias PeerSignature = ByteArray // 4096 bits = 512 bytes
typealias RemotePeerId = PeerPublicKey

const val KEY_SIZE_BYTES = 512
const val SIGNATURE_SIZE_BYTES = 512

class RemotePeer(
    val publicKey: PeerPublicKey,
) {
    val id = publicKey

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RemotePeer

        return publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        return publicKey.contentHashCode()
    }
}
