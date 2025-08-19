package p2p.domain

typealias RemotePeerReputationScore = Double
typealias RemotePeerFirstSeenUnixTimestamp = Long
typealias RemotePeerLastSeenUnixTimestamp = Long
typealias RemotePeerSuccessfulConnections = Int
typealias RemotePeerFailedConnections = Int
typealias RemotePeerValidDownloadsCompleted = Int
typealias RemotePeerBlockedStatus = Boolean

data class RemotePeerReputation(
    val remotePeerId: RemotePeerId,
    val score: RemotePeerReputationScore,
    val successfulConnections: RemotePeerSuccessfulConnections = 0,
    val failedConnections: RemotePeerFailedConnections = 0,
    val firstSeen: RemotePeerFirstSeenUnixTimestamp = System.currentTimeMillis(),
    val lastSeen: RemotePeerLastSeenUnixTimestamp = System.currentTimeMillis(),
    val validDownloadsCompleted: RemotePeerValidDownloadsCompleted = 0,
    val isBlocked: RemotePeerBlockedStatus = false
)