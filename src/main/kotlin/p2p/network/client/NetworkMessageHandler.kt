package p2p.network.client

import p2p.network.PeerNetworkInfo
import p2p.network.client.messages.NetworkMessageType

interface NetworkMessageHandler {
    fun setClientInstance(client: Client)
    fun canHandle(): Set<NetworkMessageType>
    suspend fun handle(
        type: NetworkMessageType,
        peerNetworkInfo: PeerNetworkInfo,
        sequenceNumber: Long,
        timestamp: Long,
        payload: ByteArray
    )
}