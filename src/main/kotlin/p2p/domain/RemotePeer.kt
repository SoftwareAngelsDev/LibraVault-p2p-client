package p2p.domain

import p2p.domain.wtfs.PeerPublicKey

typealias RemotePeerId = PeerPublicKey

data class RemotePeer(
    val publicKey: PeerPublicKey,
) {
    val id: RemotePeerId = publicKey
}
