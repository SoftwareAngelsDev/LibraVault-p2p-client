package p2p.network.client.messages

import p2p.helpers.ConfigurationManager
import p2p.network.PeerNetworkInfo
import p2p.network.client.Client
import p2p.network.client.NetworkMessageHandler
import p2p.network.client.VERSION
import p2p.utils.LoggerInterface
import p2p.utils.mergeByteArrays
import p2p.utils.toByteArray
import p2p.utils.toInt

class HeyMessageHandler(
    private val configs: ConfigurationManager,
    private val logger: LoggerInterface,
) : NetworkMessageHandler {
    private lateinit var client: Client

    override fun setClientInstance(client: Client) {
        this.client = client
    }

    override fun canHandle(): Set<NetworkMessageType> {
        return setOf(
            NetworkMessageType.HEY_BRO,
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
            NetworkMessageType.HEY_BRO -> {
                try {
                    val version: Int = payload.copyOfRange(0, Int.SIZE_BYTES).toInt()

                    client.addKnownPeer(
                        PeerNetworkInfo(
                            peerNetworkInfo.id,
                            peerNetworkInfo.publicIp,
                            peerNetworkInfo.publicPort,
                            version
                        )
                    )

                    client.transmit(
                        NetworkMessageType.SUP,
                        peerNetworkInfo,
                        mergeByteArrays(configs.publicKey, VERSION.toByteArray())
                    )
                } catch (e: Exception) {
                    throw InvalidPayloadException(e)
                }
            }

            else -> throw CantHandleMessage(type)
        }
    }
}