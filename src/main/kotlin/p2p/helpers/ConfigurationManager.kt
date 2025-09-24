package p2p.helpers

import p2p.domain.wtfs.PeerPrivateKey
import p2p.domain.wtfs.PeerPublicKey
import java.util.*

class ConfigurationManager {
    val repositoryAbsolutePath: String = System.getProperty("user.dir")
    val privateKey = PeerPrivateKey(Base64.getDecoder().decode(System.getProperty("user.key")))
    val publicKey = PeerPublicKey(ByteArray(PeerPrivateKey.SIZE_BYTES)) // TODO: Generate public key from private key
    val udpPort = System.getProperty("udp.port").ifBlank { "9192" }.toInt()
    val tcpPort = System.getProperty("tcp.port").ifBlank { "9193" }.toInt()

    companion object {
        // List of reliable public STUN servers
        val DEFAULT_STUN_SERVERS = listOf(
            // Google STUN servers - very reliable for UDP
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302",
            "stun:stun3.l.google.com:19302",
            "stun:stun4.l.google.com:19302",

            // Servers known to work well with both UDP and TCP
            "stun:stun.sipnet.net:3478",
            "stun:stun.voipbuster.com:3478",
            "stun:stun.ekiga.net:3478",
            "stun:stun.voipstunt.com:3478",
            "stun:stun.schlund.de:3478"
        )
    }
}