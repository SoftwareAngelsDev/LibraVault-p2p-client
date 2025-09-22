package p2p.network.client.messages

import p2p.domain.PeerPublicKey
import p2p.helpers.ConfigurationManager
import p2p.network.PeerNetworkInfo
import p2p.network.client.Client
import p2p.network.client.NetworkMessageHandler
import p2p.utils.LoggerInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class PingPongMessageHandler(
    private val configs: ConfigurationManager,
    private val logger: LoggerInterface,
) : NetworkMessageHandler {
    companion object {
        private const val PING_INTERVAL_SECONDS = 120
        private const val PING_MAX_ATTEMPTS = 3
    }

    private lateinit var client: Client

    private val failedPingAttempts = ConcurrentHashMap<PeerPublicKey, Int>()

    override fun setClientInstance(client: Client) {
        this.client = client
    }

    override fun canHandle(): Set<NetworkMessageType> {
        return setOf(
            NetworkMessageType.PING,
            NetworkMessageType.PONG,
        )
    }

    init {
        thread(start = true, isDaemon = true, name = "PingPonger") {
            while (true) {
                Thread.sleep(PING_INTERVAL_SECONDS * 1000L)

                client.getKnownPeers().forEach { peer ->
                    try {
                        val failedAttempts = failedPingAttempts.getOrDefault(peer.key, 0)
                        if (failedAttempts >= PING_MAX_ATTEMPTS) {
                            logger.info(this::javaClass.name, "Peer ${peer.key} removed due to failed ping attempts")
                            client.removeKnownPeer(peer.key)
                            failedPingAttempts.remove(peer.key)
                        } else {
                            failedPingAttempts.putIfAbsent(peer.key, 0)
                            failedPingAttempts[peer.key] = failedAttempts + 1
                            client.transmit(
                                NetworkMessageType.PING,
                                peer.value,
                                ByteArray(0)
                            )
                        }
                    } catch (e: Exception) {
                        logger.error(this::javaClass.name, "Failed to ping peer: ${e.stackTraceToString()}")
                    }
                }
            }
        }
    }

    override fun handle(
        type: NetworkMessageType,
        peerNetworkInfo: PeerNetworkInfo,
        sequenceNumber: Long,
        timestamp: Long,
        payload: ByteArray
    ) {
        when (type) {
            NetworkMessageType.PING -> {
                client.transmit(
                    NetworkMessageType.PONG,
                    peerNetworkInfo,
                    ByteArray(0)
                )
            }

            NetworkMessageType.PONG -> {
                if (!client.getKnownPeers().contains(peerNetworkInfo.id)) {
                    return // Ignore pong from unknown peer
                }

                failedPingAttempts[peerNetworkInfo.id] = 0
            }

            else -> throw CantHandleMessage(type)
        }
    }
}
