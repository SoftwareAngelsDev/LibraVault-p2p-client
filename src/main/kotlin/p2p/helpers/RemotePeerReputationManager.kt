package p2p.helpers

import p2p.domain.RemotePeerId
import p2p.domain.RemotePeerReputation
import p2p.network.Punishment
import p2p.network.Reward
import p2p.utils.Logger
import java.util.*

class RemotePeerReputationManager(val logger: Logger) {
    companion object {
        private const val TAG = "RemotePeerReputationManager"
        private val lock = Any()
    }

    fun getKnownRemotePeers(): Map<RemotePeerId, RemotePeerReputation> {
        synchronized(lock) {
            TODO()
        }
    }

    fun getPeerReputation(peerId: RemotePeerId): RemotePeerReputation? {
        synchronized(lock) {
            TODO()
            //return /* find on our DB */ ?: addNewRemotePeer(peerId)
        }
    }

    fun updatePeerReputation(peerId: RemotePeerId, p: Punishment) {
        synchronized(lock) {
            val oldPeerInfo = getPeerReputation(peerId) ?: addNewRemotePeer(peerId)

            val newReputation = oldPeerInfo.score * p.factor

            /* TODO:
                save new reputation and update the lastSeen
             */

            logger.debug(
                TAG,
                "Decreased peer reputation for ${peerId.prettyPrint()}: ${oldPeerInfo.score} -> $newReputation"
            )
        }
    }

    fun updatePeerReputation(peerId: RemotePeerId, r: Reward) {
        synchronized(lock) {
            val oldPeerInfo = getPeerReputation(peerId) ?: addNewRemotePeer(peerId)

            val newReputation = oldPeerInfo.score * r.factor

            /* TODO:
                save new reputation and update the lastSeen
             */

            logger.debug(
                TAG,
                "Increased peer reputation for ${peerId.prettyPrint()}: ${oldPeerInfo.score} -> $newReputation"
            )
        }
    }

    fun registerSuccessfulConnection(peerId: RemotePeerId) {
        synchronized(lock) {
            TODO()
        }
    }

    fun registerFailedConnection(peerId: RemotePeerId) {
        synchronized(lock) {
            TODO()
        }
    }

    private fun RemotePeerId.prettyPrint(): String {
        return Base64.getUrlEncoder().encodeToString(this).let {
            it.take(5) + "..." + it.takeLast(5)
        }
    }

    private fun addNewRemotePeer(peerId: RemotePeerId): RemotePeerReputation {
        synchronized(lock) {
            TODO()
        }
    }

}