package p2p.domain

typealias PeerReputation = Double
typealias PeerLastSeenUnixTimestamp = Long
typealias SuccessfulConnections = Int
typealias FailedConnections = Int

data class RemotePeerMetadata(
    val reputation: PeerReputation,
    val successfulConnections: SuccessfulConnections = 0,
    val failedConnections: FailedConnections = 0,
    val lastSeen: PeerLastSeenUnixTimestamp = System.currentTimeMillis(),
)