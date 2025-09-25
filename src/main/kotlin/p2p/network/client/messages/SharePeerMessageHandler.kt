package p2p.network.client.messages

import p2p.domain.RemotePeerId
import p2p.domain.wtfs.PeerPublicKey
import p2p.helpers.ConfigurationManager
import p2p.network.PeerNetworkInfo
import p2p.network.client.Client
import p2p.network.client.NetworkMessageHandler
import p2p.utils.*
import java.util.concurrent.ConcurrentHashMap

class SharePeerMessageHandler(
    private val configs: ConfigurationManager,
    private val logger: LoggerInterface,
) : NetworkMessageHandler {
    companion object {
        const val MAX_AGE_MINUTES = 10
    }

    private lateinit var client: Client

    private val broadcasted = ConcurrentHashMap<RemotePeerId, Long>()

    private fun wasRecentlyBroadcasted(peerId: RemotePeerId): Boolean {
        return broadcasted[peerId]?.let { System.currentTimeMillis() - it < MAX_AGE_MINUTES } ?: false
    }

    override fun setClientInstance(client: Client) {
        this.client = client
        client.addActivityListener(
            object : Client.ActivityListener {
                override suspend fun onPeerAdded(addedPeer: PeerNetworkInfo) {
                    val peers = client.getKnownPeers().values

                    // Share added peer with all known peers
                    if (!wasRecentlyBroadcasted(addedPeer.id)) {
                        peers.forEach { p ->
                            client.transmit(
                                type = NetworkMessageType.SHARE_PEER,
                                destination = p,
                                payload = createPayload(addedPeer),
                            )
                        }
                    }

                    // Share all known peers with the added peer
                    peers.forEach { p ->
                        client.transmit(
                            type = NetworkMessageType.SHARE_PEER,
                            destination = addedPeer,
                            payload = createPayload(p),
                        )
                    }
                }
            }
        )
    }

    override fun canHandle(): Set<NetworkMessageType> {
        return setOf(
            NetworkMessageType.SHARE_PEER,
        )
    }

    override suspend fun handle(
        type: NetworkMessageType,
        peerNetworkInfo: PeerNetworkInfo,
        sequenceNumber: Long,
        timestamp: Long,
        payload: ByteArray
    ) {
        when (type) {
            NetworkMessageType.SHARE_PEER -> {
                val receivedPeer: PeerNetworkInfo = parsePayload(payload)

                client.addKnownPeer(receivedPeer)
            }

            else -> throw CantHandleMessage(type)
        }
    }

    private fun parsePayload(payload: ByteArray): PeerNetworkInfo {
        try {
            val id = PeerPublicKey(payload.copyOfRange(0, RemotePeerId.SIZE_BYTES))
            val publicIp =
                String(payload.copyOfRange(RemotePeerId.SIZE_BYTES, RemotePeerId.SIZE_BYTES + Int.SIZE_BYTES))
            val publicPort = payload.copyOfRange(
                RemotePeerId.SIZE_BYTES + Int.SIZE_BYTES,
                RemotePeerId.SIZE_BYTES + Int.SIZE_BYTES + Short.SIZE_BYTES
            ).toShort()
            val version = payload.copyOfRange(
                RemotePeerId.SIZE_BYTES + Int.SIZE_BYTES + Short.SIZE_BYTES,
                RemotePeerId.SIZE_BYTES + Int.SIZE_BYTES + Short.SIZE_BYTES + Int.SIZE_BYTES
            ).toInt()

            return PeerNetworkInfo(id, publicIp, publicPort, version)
        } catch (e: Exception) {
            throw InvalidPayloadException(e)
        }
    }

    fun createPayload(added: PeerNetworkInfo): ByteArray {
        return mergeByteArrays(
            added.id.toByteArray(),
            added.publicIp.toByteArray(),
            added.publicPort.toByteArray(),
            added.version.toByteArray(),
        )
    }
}
