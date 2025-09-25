package p2p.helpers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import p2p.database.config.DatabaseConfig
import p2p.database.repositories.PeerReputationRepository
import p2p.domain.RemotePeerId
import p2p.domain.RemotePeerReputation
import p2p.network.Punishment
import p2p.network.Reward
import p2p.utils.LoggerInterface

class RemotePeerReputationManager(private val logger: LoggerInterface) {
    companion object {
        private const val TAG = "RemotePeerReputationManager"
        private const val DEFAULT_REPUTATION_SCORE = 1.0
    }

    private val dbConfig = DatabaseConfig(logger)
    private val peerRepository = PeerReputationRepository(dbConfig, logger)

    suspend fun getKnownRemotePeers(): Map<RemotePeerId, RemotePeerReputation> = withContext(Dispatchers.IO) {
        peerRepository.getAllPeers()
    }

    suspend fun getPeerReputation(peerId: RemotePeerId): RemotePeerReputation? = withContext(Dispatchers.IO) {
        peerRepository.getPeerReputation(peerId)
    }

    suspend fun updatePeerReputation(peerId: RemotePeerId, p: Punishment) = withContext(Dispatchers.IO) {
        val oldPeerInfo = getPeerReputation(peerId) ?: addNewRemotePeer(peerId)
        val newReputation = oldPeerInfo.score * p.factor

        val updatedPeer = oldPeerInfo.copy(
            score = newReputation,
            lastSeen = System.currentTimeMillis()
        )

        peerRepository.savePeerReputation(updatedPeer)

        logger.debug(
            TAG,
            "Decreased peer reputation for $peerId: ${oldPeerInfo.score} -> $newReputation"
        )
    }

    suspend fun updatePeerReputation(peerId: RemotePeerId, r: Reward) = withContext(Dispatchers.IO) {
        val oldPeerInfo = getPeerReputation(peerId) ?: addNewRemotePeer(peerId)
        val newReputation = oldPeerInfo.score * r.factor

        val updatedPeer = oldPeerInfo.copy(
            score = newReputation,
            lastSeen = System.currentTimeMillis()
        )

        peerRepository.savePeerReputation(updatedPeer)

        logger.debug(
            TAG,
            "Increased peer reputation for $peerId: ${oldPeerInfo.score} -> $newReputation"
        )
    }

    suspend fun registerSuccessfulConnection(peerId: RemotePeerId) = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        val peerInfo = getPeerReputation(peerId)

        if (peerInfo == null) {
            val newPeer = RemotePeerReputation(
                remotePeerId = peerId,
                score = DEFAULT_REPUTATION_SCORE,
                successfulConnections = 1,
                firstSeen = currentTime,
                lastSeen = currentTime
            )
            peerRepository.insertPeerReputation(newPeer)
        } else {
            peerRepository.incrementSuccessfulConnections(peerId, currentTime)
        }
    }

    suspend fun registerFailedConnection(peerId: RemotePeerId) = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        val peerInfo = getPeerReputation(peerId)

        if (peerInfo == null) {
            val newPeer = RemotePeerReputation(
                remotePeerId = peerId,
                score = DEFAULT_REPUTATION_SCORE,
                failedConnections = 1,
                firstSeen = currentTime,
                lastSeen = currentTime
            )
            peerRepository.insertPeerReputation(newPeer)
        } else {
            peerRepository.incrementFailedConnections(peerId, currentTime)
        }
    }

    suspend fun blockPeer(peerId: RemotePeerId) = withContext(Dispatchers.IO) {
        val peerInfo = getPeerReputation(peerId)
        val currentTime = System.currentTimeMillis()

        if (peerInfo == null) {
            val newPeer = RemotePeerReputation(
                remotePeerId = peerId,
                score = DEFAULT_REPUTATION_SCORE,
                isBlocked = true,
                firstSeen = currentTime,
                lastSeen = currentTime
            )
            peerRepository.insertPeerReputation(newPeer)
            logger.info(TAG, "New peer created and blocked: $peerId")
        } else {
            peerRepository.blockPeer(peerId, currentTime)
            logger.info(TAG, "Peer blocked: $peerId")
        }
    }

    suspend fun unblockPeer(peerId: RemotePeerId) = withContext(Dispatchers.IO) {
        val peerInfo = getPeerReputation(peerId)
        val currentTime = System.currentTimeMillis()

        if (peerInfo == null) {
            val newPeer = RemotePeerReputation(
                remotePeerId = peerId,
                score = DEFAULT_REPUTATION_SCORE,
                firstSeen = currentTime,
                lastSeen = currentTime
            )
            peerRepository.insertPeerReputation(newPeer)
            logger.info(TAG, "New peer created (not blocked): $peerId")
        } else {
            peerRepository.unblockPeer(peerId, currentTime)
            logger.info(TAG, "Peer unblocked: $peerId")
        }
    }

    suspend fun getBlockedPeers(): List<RemotePeerReputation> = withContext(Dispatchers.IO) {
        peerRepository.getBlockedPeers()
    }

    suspend fun isPeerBlocked(peerId: RemotePeerId): Boolean = withContext(Dispatchers.IO) {
        val peerInfo = getPeerReputation(peerId)
        peerInfo?.isBlocked ?: false
    }

    private suspend fun addNewRemotePeer(peerId: RemotePeerId): RemotePeerReputation = withContext(Dispatchers.IO) {
        val newPeer = RemotePeerReputation(
            remotePeerId = peerId,
            score = DEFAULT_REPUTATION_SCORE,
            firstSeen = System.currentTimeMillis(),
            lastSeen = System.currentTimeMillis()
        )

        peerRepository.insertPeerReputation(newPeer)
    }
}