package p2p.network.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import p2p.domain.wtfs.PeerPublicKey
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
    private val transmitter: ClientTransmitter = UdpClientTransmitter(configs, logger, this)

    private val handlers = HashMap<NetworkMessageType, NetworkMessageHandler>()
    private val knownPeers = ConcurrentHashMap<PeerPublicKey, PeerNetworkInfo>()
    private val activityListeners = HashSet<ActivityListener>()

    fun addActivityListener(listener: ActivityListener) {
        activityListeners.add(listener)
    }

    fun getKnownPeers(): Map<PeerPublicKey, PeerNetworkInfo> {
        return knownPeers
    }

    suspend fun addKnownPeer(peer: PeerNetworkInfo) {
        val old = knownPeers[peer.id]
        if (old != null && old.sameNetworkAddressAs(peer)) {
            // Peer is already known
            return
        }

        knownPeers[peer.id] = peer
        activityListeners.forEach { it.onPeerAdded(peer) }
        logger.info(this::javaClass.name, "New peer added: $peer")
    }

    suspend fun removeKnownPeer(peerId: PeerPublicKey) {
        val removed = knownPeers.remove(peerId)
        if (removed != null) {
            activityListeners.forEach { it.onPeerRemoved(removed) }
            logger.info(this::javaClass.name, "Peer removed: $removed")
        }
    }

    fun addMessageHandler(messageHandler: NetworkMessageHandler) {
        messageHandler.canHandle().forEach { t -> handlers[t] = messageHandler }
        messageHandler.setClientInstance(this)
    }

    suspend fun transmit(type: NetworkMessageType, destination: PeerNetworkInfo, payload: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                transmitter.transmit(type, destination, payload)
            } catch (t: Throwable) {
                logger.error(this::javaClass.name, "Failed to transmit message: ${t.stackTraceToString()}")
            }
        }
    }

    fun start() {
        transmitter.start()
        logger.info("Client", "Client started successfully")
    }

    fun stop() {
        transmitter.stop()
        logger.info("Client", "Client stopped successfully")
    }

    internal suspend fun handleMessage(message: NetworkMessage) {
        // Update peer info if it has changed
        val known = knownPeers[message.peerNetworkInfo.id]
        if (known != null) {
            if (known.publicIp != message.peerNetworkInfo.publicIp || known.publicPort != message.peerNetworkInfo.publicPort) {
                logger.info(
                    "Client",
                    "Peer ${message.peerNetworkInfo.id} has changed IP or port - ${known.publicIp}:${known.publicPort} -> ${message.peerNetworkInfo.publicIp}:${message.peerNetworkInfo.publicPort}"
                )
                knownPeers[message.peerNetworkInfo.id] = PeerNetworkInfo(
                    id = message.peerNetworkInfo.id,
                    publicIp = message.peerNetworkInfo.publicIp,
                    publicPort = message.peerNetworkInfo.publicPort,
                    version = known.version
                )
            }
        }

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

    interface ActivityListener {
        suspend fun onPeerAdded(addedPeer: PeerNetworkInfo) {}
        suspend fun onPeerRemoved(removedPeer: PeerNetworkInfo) {}
    }
}