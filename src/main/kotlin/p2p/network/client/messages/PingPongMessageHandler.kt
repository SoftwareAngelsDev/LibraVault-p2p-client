package p2p.network.client.messages

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import p2p.domain.wtfs.PeerPublicKey
import p2p.helpers.ConfigurationManager
import p2p.network.PeerNetworkInfo
import p2p.network.client.Client
import p2p.network.client.NetworkMessageHandler
import p2p.utils.LoggerInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class PingPongMessageHandler(
    private val configs: ConfigurationManager,
    private val logger: LoggerInterface,
) : NetworkMessageHandler {
    companion object {
        private const val PING_INTERVAL_SECONDS = 120L
        private const val PING_MAX_ATTEMPTS = 4
        private const val LAST_HEARD_OF_TIMEOUT_MILLIS = ((PING_INTERVAL_SECONDS * 1000L) * PING_MAX_ATTEMPTS)
    }

    private lateinit var client: Client

    private val failedPingAttempts = ConcurrentHashMap<PeerPublicKey, PingPongStatus>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "PingPonger").apply { isDaemon = true }
    }

    override fun setClientInstance(client: Client) {
        this.client = client

        // Start after client is definitely set
        scheduler.scheduleAtFixedRate(Runnable {
            GlobalScope.launch { tick() }
        }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    override fun canHandle(): Set<NetworkMessageType> {
        return setOf(
            NetworkMessageType.PING,
            NetworkMessageType.PONG,
        )
    }

    private suspend fun tick() {
        if (!isMuted(configs.publicKey)) {
            client
                .getKnownPeers()
                .forEach { peer ->
                    try {
                        val hasTimedOut = failedPingAttempts[peer.key]?.hasTimedOut() ?: false
                        if (hasTimedOut) {
                            logger.info(
                                javaClass.name,
                                "Peer ${peer.key} removed due to failed ping attempts"
                            )
                            client.removeKnownPeer(peer.key)
                            failedPingAttempts.remove(peer.key)
                        } else {
                            client.transmit(
                                NetworkMessageType.PING,
                                peer.value,
                                ByteArray(0)
                            )
                            failedPingAttempts.putIfAbsent(peer.key, PingPongStatus())
                        }
                    } catch (e: Exception) {
                        logger.error(javaClass.name, "Failed to ping peer: ${e.stackTraceToString()}")
                    }
                }
        }

        // Remove peers that are not in the known peers list, or that have not been heard of for too long
        client.getKnownPeers().keys.let { knownPeerKeys ->
            val keysToRemove = failedPingAttempts.filter { (id, pingPongStatus) ->
                id !in knownPeerKeys || pingPongStatus.hasTimedOut()
            }
            keysToRemove.forEach { failedPingAttempts.remove(it.key) }
        }
    }

    private fun isMuted(id: PeerPublicKey): Boolean {
        // In order to save bandwidth, only even nodes will ping
        // If the last byte of the public key is odd, then it will not ping
        return id.toByteArray().last().toInt() % 2 != 0
    }

    override suspend fun handle(
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
                // No action needed
            }

            else -> throw CantHandleMessage(type)
        }

        if (client.getKnownPeers().containsKey(peerNetworkInfo.id)) {
            // The peer is alive
            failedPingAttempts[peerNetworkInfo.id] = PingPongStatus()
        }
    }

    private data class PingPongStatus(val lastAttemptTime: Long = System.currentTimeMillis()) {
        fun hasTimedOut(): Boolean {
            return System.currentTimeMillis() - lastAttemptTime > LAST_HEARD_OF_TIMEOUT_MILLIS
        }
    }
}
