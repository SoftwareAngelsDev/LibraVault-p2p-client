package p2p.network.client.messages

import p2p.helpers.ConfigurationManager
import p2p.network.PeerNetworkInfo
import p2p.network.client.Client
import p2p.network.client.NetworkMessageHandler
import p2p.utils.LoggerInterface

class SeeYaBroMessageHandler(
    private val configs: ConfigurationManager,
    private val logger: LoggerInterface,
) : NetworkMessageHandler {
    private lateinit var client: Client

    override fun setClientInstance(client: Client) {
        this.client = client
    }

    override fun canHandle(): Set<NetworkMessageType> {
        return setOf(
            NetworkMessageType.SEE_YA_BRO,
        )
    }

    override fun handle(
        type: NetworkMessageType,
        peerNetworkInfo: PeerNetworkInfo,
        sequenceNumber: Long,
        timestamp: Long,
        payload: ByteArray
    ) {
        when (type) {
            NetworkMessageType.SEE_YA_BRO -> {
                client.removeKnownPeer(peerNetworkInfo.id)
            }

            else -> throw CantHandleMessage(type)
        }
    }
}
