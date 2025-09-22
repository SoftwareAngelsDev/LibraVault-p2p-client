package p2p.network.client

import p2p.domain.PeerPublicKey
import p2p.helpers.ConfigurationManager
import p2p.helpers.RemotePeerReputationManager
import p2p.network.PeerNetworkInfo
import p2p.network.client.messages.NetworkMessage
import p2p.network.client.messages.NetworkMessageType
import p2p.utils.LoggerInterface
import java.util.concurrent.ConcurrentHashMap

const val VERSION = 1

class Client(
    private val configs: ConfigurationManager,
    private val logger: LoggerInterface,
    private val remotePeerReputationManager: RemotePeerReputationManager,
) {
    private val handlers = HashMap<NetworkMessageType, NetworkMessageHandler>()
    private val knownPeers = ConcurrentHashMap<PeerPublicKey, PeerNetworkInfo>()

    fun getKnownPeers(): Map<PeerPublicKey, PeerNetworkInfo> {
        return HashMap(knownPeers)
    }

    fun addKnownPeer(peer: PeerNetworkInfo) {
        knownPeers[peer.id] = peer
        logger.info(this::javaClass.name, "New peer added: $peer")
    }

    fun removeKnownPeer(peerId: PeerPublicKey) {
        val removed = knownPeers.remove(peerId)
        if (removed != null) {
            logger.info(this::javaClass.name, "Peer removed: $removed")
        }
    }

    fun addMessageHandler(messageHandler: NetworkMessageHandler) {
        messageHandler.canHandle().forEach { t -> handlers[t] = messageHandler }
        messageHandler.setClientInstance(this)
    }

    fun transmit(type: NetworkMessageType, destination: PeerNetworkInfo, payload: ByteArray) {
// Sends the message to the peer
    }

    fun start() {
// Listens for UDP messages in port from configs
    }

    fun stop() {
// Closes the socket
    }

    fun handle(message: NetworkMessage) {
        val handler = handlers[message.type]
        if (handler == null) {
            logger.error("Client", "No handler found for message: $message")
            return
        }

        handler.handle(
            message.type,
            message.peerNetworkInfo,
            message.sequenceNumber,
            message.timestamp,
            message.payload
        )
    }
}