package p2p.domain

import p2p.domain.wtfs.PeerPublicKey

typealias PeerSignature = ByteArray // 4096 bits = 512 bytes
typealias RemotePeerId = PeerPublicKey

data class RemotePeer(
    val publicKey: PeerPublicKey,
) {
    val id: RemotePeerId = publicKey

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RemotePeer

        return publicKey == other.publicKey
    }

    override fun hashCode(): Int {
        return publicKey.hashCode()
    }
}
