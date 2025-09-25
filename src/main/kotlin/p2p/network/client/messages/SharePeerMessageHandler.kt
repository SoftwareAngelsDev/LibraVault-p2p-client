package p2p.network.client.messages

import p2p.domain.RemotePeerId
import p2p.domain.wtfs.PeerPublicKey
import p2p.helpers.ConfigurationManager
import p2p.network.PeerNetworkInfo
import p2p.network.client.Client
import p2p.network.client.NetworkMessageHandler
import p2p.utils.*
import java.net.InetAddress
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

                    // Share added peer with all known peers except the added peer itself
                    if (!wasRecentlyBroadcasted(addedPeer.id) || isMyself(addedPeer.id)) {
                        peers.filter { p -> p.id != addedPeer.id }.forEach { p ->
                            client.transmit(
                                type = NetworkMessageType.SHARE_PEER,
                                destination = p,
                                payload = createPayload(addedPeer),
                            )
                        }
                    }

                    // Share all the other peers I know with the added peer
                    peers.filter { p -> p.id != addedPeer.id }.forEach { p ->
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
                val receivedPeer = parsePayload(payload)

                // Don't add ourselves to our known peers
                if (!isMyself(receivedPeer.id)) {
                    client.addKnownPeer(receivedPeer)
                }
            }

            else -> throw CantHandleMessage(type)
        }
    }

    private fun parsePayload(payload: ByteArray): PeerNetworkInfo {
        try {
            var offset = 0

            // Read peer ID (512 bytes)
            val id = PeerPublicKey(payload.copyOfRange(offset, offset + RemotePeerId.SIZE_BYTES))
            offset += RemotePeerId.SIZE_BYTES

            // Read IP address (4 bytes for IPv4)
            val ipBytes = payload.copyOfRange(offset, offset + 4)
            val publicIp = InetAddress.getByAddress(ipBytes).hostAddress
            offset += 4

            // Read port (2 bytes)
            val publicPort = payload.copyOfRange(offset, offset + Short.SIZE_BYTES).toShort()
            offset += Short.SIZE_BYTES

            // Read version (4 bytes)
            val version = payload.copyOfRange(offset, offset + Int.SIZE_BYTES).toInt()

            return PeerNetworkInfo(id, publicIp, publicPort, version)
        } catch (e: Exception) {
            throw InvalidPayloadException(e)
        }
    }

    private fun createPayload(added: PeerNetworkInfo): ByteArray {
        val ipBytes = InetAddress.getByName(added.publicIp).address  // Convert IP to 4 bytes
        return mergeByteArrays(
            added.id.toByteArray(),        // 512 bytes
            ipBytes,                       // 4 bytes (IPv4)
            added.publicPort.toByteArray(), // 2 bytes
            added.version.toByteArray(),   // 4 bytes
        )
    }

    private fun isMyself(id: PeerPublicKey): Boolean {
        return id == configs.publicKey
    }
}
