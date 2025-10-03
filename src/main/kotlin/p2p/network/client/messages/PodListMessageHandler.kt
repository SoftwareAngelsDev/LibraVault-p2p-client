package p2p.network.client.messages

import p2p.domain.PeerSignature
import p2p.domain.wtfs.PeerPublicKey
import p2p.domain.wtfs.PodMetadata
import p2p.helpers.ConfigurationManager
import p2p.helpers.CypherUtils
import p2p.helpers.FilePodMerkleTreeHelper.Companion.MERKLE_HASH_ROOT_SIZE_BYTES
import p2p.helpers.MerkleHashRoot
import p2p.network.PeerNetworkInfo
import p2p.network.client.Client
import p2p.network.client.NetworkMessageHandler
import p2p.utils.*

class PodListMessageHandler(
    private val configs: ConfigurationManager,
    private val logger: LoggerInterface,
) : NetworkMessageHandler {
    private lateinit var client: Client

    override fun setClientInstance(client: Client) {
        this.client = client
    }

    override fun canHandle(): Set<NetworkMessageType> {
        return setOf(
            NetworkMessageType.SHARE_POD,
        )
    }

    suspend fun sendPod(podMetadata: PodMetadata, destination: PeerNetworkInfo) {
        client.transmit(
            type = NetworkMessageType.SHARE_POD,
            destination = destination,
            payload = serializePayload(podMetadata),
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
            NetworkMessageType.SHARE_POD -> {
                val receivedPod: PodMetadata = parsePayload(payload)
                
                // Validate signature before processing
                if (CypherUtils.validatePodMetadata(receivedPod)) {
                    logger.info(
                        "PodListMessageHandler",
                        "Received valid signed pod from $peerNetworkInfo -> $receivedPod"
                    )
                    client.addPod(receivedPod)
                } else {
                    logger.warn(
                        "PodListMessageHandler",
                        "Received pod with invalid signature from $peerNetworkInfo, ignoring"
                    )
                }
            }

            else -> throw CantHandleMessage(type)
        }
    }

    private fun serializePayload(podMetadata: PodMetadata): ByteArray {
        val ownerBytes = podMetadata.owner.toByteArray()
        val numberBytes = podMetadata.number.toByteArray()
        val merkleHashBytes = podMetadata.merkleHashRoot
        val updatedAtBytes = podMetadata.updatedAt.toByteArray()
        val ownerSignatureBytes = podMetadata.ownerSignature

        return mergeByteArrays(ownerBytes, numberBytes, merkleHashBytes, updatedAtBytes, ownerSignatureBytes)
    }

    private fun parsePayload(payload: ByteArray): PodMetadata {
        if (payload.size != EXPECTED_PAYLOAD_SIZE) {
            throw IllegalArgumentException("Invalid payload size: ${payload.size}, expected $EXPECTED_PAYLOAD_SIZE")
        }

        // Extract byte ranges for each field
        val ownerBytes = payload.copyOfRange(0, OWNER_SIZE)
        val numberBytes = payload.copyOfRange(OWNER_SIZE, OWNER_SIZE + NUMBER_SIZE)
        val merkleHashBytes = payload.copyOfRange(OWNER_SIZE + NUMBER_SIZE, OWNER_SIZE + NUMBER_SIZE + MERKLE_HASH_SIZE)
        val updatedAtBytes = payload.copyOfRange(OWNER_SIZE + NUMBER_SIZE + MERKLE_HASH_SIZE, OWNER_SIZE + NUMBER_SIZE + MERKLE_HASH_SIZE + UPDATED_AT_SIZE)
        val ownerSignatureBytes = payload.copyOfRange(OWNER_SIZE + NUMBER_SIZE + MERKLE_HASH_SIZE + UPDATED_AT_SIZE, EXPECTED_PAYLOAD_SIZE)

        // Convert back to original types
        val owner = PeerPublicKey(ownerBytes)
        val number = numberBytes.toInt()
        val merkleHashRoot: MerkleHashRoot = merkleHashBytes
        val updatedAt = updatedAtBytes.toLong()
        val ownerSignature: PeerSignature = ownerSignatureBytes

        return PodMetadata(owner, number, merkleHashRoot, updatedAt, ownerSignature)
    }

    companion object {
        private const val OWNER_SIZE = PeerPublicKey.SIZE_BYTES
        private const val NUMBER_SIZE = Int.SIZE_BYTES
        private const val MERKLE_HASH_SIZE = MERKLE_HASH_ROOT_SIZE_BYTES
        private const val UPDATED_AT_SIZE = Long.SIZE_BYTES
        private const val OWNER_SIGNATURE_SIZE = PeerPublicKey.SIZE_BYTES // Same size as RSA 4096 signature
        private const val EXPECTED_PAYLOAD_SIZE = OWNER_SIZE + NUMBER_SIZE + MERKLE_HASH_SIZE + UPDATED_AT_SIZE + OWNER_SIGNATURE_SIZE
    }

}