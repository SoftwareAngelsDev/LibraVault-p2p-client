package p2p.network.client.messages

import p2p.domain.PeerSignature
import p2p.network.PeerNetworkInfo

class NetworkMessage(
    val type: NetworkMessageType,
    val payload: ByteArray,
    val peerNetworkInfo: PeerNetworkInfo,
    val timestamp: Long,
    val sequenceNumber: Long,
    val signature: PeerSignature,
) {
    override fun toString(): String {
        return "[type: ${this::class.simpleName} | peer: ${peerNetworkInfo.id} | payload size: ${payload.size}]"
    }
}

enum class NetworkMessageType {
    // Handshake messages
    HEY_BRO,
    SUP,
    SEE_YA_BRO,

    // Keep Alive
    PING,
    PONG,

    // Gossiping
    SHARE_PEER,
    SHARE_POD,
}