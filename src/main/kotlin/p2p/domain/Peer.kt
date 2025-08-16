package p2p.domain

typealias PeerIpAddress = String
typealias PeerPort = Int

data class Peer(
    val host: PeerIpAddress,
    val port: PeerPort
)
