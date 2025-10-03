package p2p.domain.wtfs

import p2p.domain.PeerSignature
import p2p.helpers.MerkleHashRoot
import p2p.utils.prettyPrint

/**
 * Represents metadata about a stored pod in the database.
 * This is different from FilePod which represents the actual pod content.
 */
data class PodMetadata(
    val owner: PeerPublicKey,
    val number: Int,
    val merkleHashRoot: MerkleHashRoot,
    val updatedAt: Long,
    val ownerSignature: PeerSignature
) {
    override fun toString(): String {
        return "[owner: ${owner}, number: $number, updatedAt: $updatedAt, merkleHashRoot: ${merkleHashRoot.prettyPrint()}]"
    }
}

