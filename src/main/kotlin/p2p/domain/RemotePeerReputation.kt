package p2p.domain

import p2p.domain.wtfs.UnixTimestamp

typealias RemotePeerReputationScore = Double
typealias RemotePeerSuccessfulConnections = Int
typealias RemotePeerFailedConnections = Int
typealias RemotePeerValidDownloadsCompleted = Int
typealias RemotePeerBlockedStatus = Boolean

data class RemotePeerReputation(
    val remotePeerId: RemotePeerId,
    val score: RemotePeerReputationScore,
    val successfulConnections: RemotePeerSuccessfulConnections = 0,
    val failedConnections: RemotePeerFailedConnections = 0,
    val firstSeen: UnixTimestamp = System.currentTimeMillis(),
    val lastSeen: UnixTimestamp = System.currentTimeMillis(),
    val validDownloadsCompleted: RemotePeerValidDownloadsCompleted = 0,
    val isBlocked: RemotePeerBlockedStatus = false
)