package p2p.helpers

import p2p.domain.wtfs.FilePod.Companion.POD_FIXED_SIZE_BYTES
import p2p.domain.wtfs.FilePodEncoded
import java.security.MessageDigest
import kotlin.math.ceil

typealias MerkleHashRoot = ByteArray // 32 bytes

class FilePodMerkleTreeHelper {
    companion object {
        val CHUNK_SIZE_BYTES: Int = ceil(POD_FIXED_SIZE_BYTES / 100.0).toInt()

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        private fun concat(a: ByteArray, b: ByteArray): ByteArray = a + b
    }

    /** Build Merkle tree root from encoded file */
    fun calculateMerkleHashRoot(pod: FilePodEncoded): MerkleHashRoot {
        if (pod.size != POD_FIXED_SIZE_BYTES) {
            throw IllegalArgumentException("Invalid pod!")
        }

        val leaves = pod.toListOfChunks(CHUNK_SIZE_BYTES).map { sha256(it) }
        return calculateRoot(leaves)
    }

    /** Generate Merkle proof (sibling hashes) for a given chunk index */
    fun generateProof(pod: FilePodEncoded, chunkIndex: Int): List<ByteArray> {
        if (chunkIndex !in 0..<CHUNK_SIZE_BYTES) {
            throw IllegalArgumentException("Invalid chunk!")
        }

        val leaves = pod.toListOfChunks(CHUNK_SIZE_BYTES).map { sha256(it) }
        var idx = chunkIndex
        var level = leaves
        val proof = mutableListOf<ByteArray>()

        while (level.size > 1) {
            val parentLevel = mutableListOf<ByteArray>()
            for (i in level.indices step 2) {
                if (i + 1 < level.size) {
                    val left = level[i]
                    val right = level[i + 1]
                    parentLevel.add(sha256(concat(left, right)))
                    // If current index is here → add sibling
                    if (i == idx || i + 1 == idx) {
                        val sibling = if (i == idx) right else left
                        proof.add(sibling)
                        idx = i / 2
                    }
                } else {
                    // odd promotion
                    parentLevel.add(sha256(level[i]))
                    if (i == idx) {
                        // no sibling in this case
                        idx = i / 2
                    }
                }
            }
            level = parentLevel
        }
        return proof
    }

    /** Verify a Merkle proof for a given chunk */
    fun validateProof(
        chunkData: ByteArray,
        proof: List<ByteArray>,
        root: MerkleHashRoot,
        chunkIndex: Int
    ): Boolean {
        if (chunkIndex !in 0..<CHUNK_SIZE_BYTES) {
            throw IllegalArgumentException("Invalid chunk!")
        }

        var hash = sha256(chunkData)
        var idx = chunkIndex
        proof.forEach { sibling ->
            hash = if (idx % 2 == 0) sha256(concat(hash, sibling))
            else sha256(concat(sibling, hash))
            idx /= 2
        }
        return hash.contentEquals(root)
    }

    /** Split data into chunks */
    private fun ByteArray.toListOfChunks(size: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < this.size) {
            val end = (offset + size).coerceAtMost(this.size)
            out.add(this.copyOfRange(offset, end))
            offset += size
        }
        return out
    }

    /** Build Merkle tree and return list with root as first element */
    private fun calculateRoot(leaves: List<ByteArray>): MerkleHashRoot {
        var level = leaves
        while (level.size > 1) {
            level = level
                .chunked(2)
                .map {
                    if (it.size == 2) sha256(concat(it[0], it[1]))
                    else sha256(it[0]) // odd promotion
                }
        }

        return level.first()
    }
}
