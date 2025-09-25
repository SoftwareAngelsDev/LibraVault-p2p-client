package p2p.network

import p2p.domain.wtfs.PeerPublicKey

const val UNSET_VERSION = 0

data class PeerNetworkInfo(
    val id: PeerPublicKey,
    val publicIp: String,
    val publicPort: Short,
    val version: Int,
) {
    override fun toString(): String {
        return "[public ip: $publicIp | public port: $publicPort | version: $version | id: $id]"
    }

    fun sameNetworkAddressAs(other: PeerNetworkInfo): Boolean {
        return publicIp == other.publicIp && publicPort == other.publicPort
    }
}