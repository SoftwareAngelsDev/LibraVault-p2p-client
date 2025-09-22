package p2p.network

import p2p.domain.PeerPublicKey
import java.util.*

class PeerNetworkInfo(
    val id: PeerPublicKey,
    val publicIp: String,
    val publicPort: Short,
    val version: Int,
) {
    override fun toString(): String {
        return "[public ip: $publicIp | public port: $publicPort | version: $version | id: ${
            id.let {
                Base64.getEncoder().encodeToString(it)
            }
        }]"
    }
}