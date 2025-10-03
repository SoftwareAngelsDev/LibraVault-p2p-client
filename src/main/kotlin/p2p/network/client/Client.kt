package p2p.network.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import p2p.database.repositories.PodsRepository
import p2p.domain.wtfs.PeerPublicKey
import p2p.domain.wtfs.PodMetadata
import p2p.helpers.ConfigurationManager
import p2p.helpers.RemotePeerReputationManager
import p2p.network.PeerNetworkInfo
import p2p.network.UNSET_VERSION
import p2p.network.client.messages.NetworkMessage
import p2p.network.client.messages.NetworkMessageType
import p2p.utils.LoggerInterface
import java.util.concurrent.ConcurrentHashMap

const val VERSION = 1

class Client(
    private val configs: ConfigurationManager,
    private val logger: LoggerInterface,
    private val remotePeerReputationManager: RemotePeerReputationManager,
    private val podsRepository: PodsRepository,
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
        if (old != null) {
            if (old.sameNetworkAddressAs(peer)) {
                // Peer is already known
                return
            } else {
                logger.info(
                    "Client",
                    "Peer ${peer.id} has changed IP or port - ${old.publicIp}:${old.publicPort} -> ${peer.publicIp}:${peer.publicPort}"
                )
            }
        }

        val version = if (peer.version == UNSET_VERSION)
            old?.version ?: throw IllegalArgumentException("Peer version is unset")
        else
            peer.version

        knownPeers[peer.id] = peer.copy(version = version)
        activityListeners.forEach { it.onPeerAdded(peer) }
        logger.info(this.javaClass.simpleName, "New peer added: $peer")
    }

    suspend fun removeKnownPeer(peerId: PeerPublicKey) {
        val removed = knownPeers.remove(peerId)
        if (removed != null) {
            activityListeners.forEach { it.onPeerRemoved(removed) }
            logger.info(this.javaClass.simpleName, "Peer removed: $removed")
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
                logger.error(this.javaClass.simpleName, "Failed to transmit message: ${t.stackTraceToString()}")
            }
        }
    }

    fun start() {
        transmitter.start()
        logger.info("Client", "Client started successfully ${getNetworkIdentity()}")
    }

    fun stop() {
        transmitter.stop()
        logger.info("Client", "Client stopped successfully ${getNetworkIdentity()}")
    }

    private fun getNetworkIdentity(): String {
        val peerId = configs.publicKey.toString().take(10) + "..."
        return "(Port: ${configs.udpPort}, ID: $peerId)"
    }

    internal suspend fun handleMessage(message: NetworkMessage) {
        // Update peer info if it has changed
        val shouldUpdatePeerInfo = knownPeers[message.peerNetworkInfo.id] != null
        if (shouldUpdatePeerInfo) {
            addKnownPeer(
                PeerNetworkInfo(
                    id = message.peerNetworkInfo.id,
                    publicIp = message.peerNetworkInfo.publicIp,
                    publicPort = message.peerNetworkInfo.publicPort,
                    version = UNSET_VERSION
                )
            )
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

    suspend fun addPod(receivedPod: PodMetadata) {
        podsRepository.upsertPod(receivedPod)
    }

    interface ActivityListener {
        suspend fun onPeerAdded(addedPeer: PeerNetworkInfo) {}
        suspend fun onPeerRemoved(removedPeer: PeerNetworkInfo) {}
    }
}