package p2p.domain

abstract class Chunk(
    val fileId: FileId,
    val metadata: ChunkMetadata,
    val path: String
) {
    companion object {
        const val CHUNK_SIZE_BYTES = 100 * 1024 * 1024 // 100MB
    }

    abstract fun getTransmittingData(): ByteArray

    abstract fun getChunkData(): ByteArray
}

class FileId(
    val userPublicKey: ByteArray,
    val hash: ByteArray,
    val numberOfChunks: Long,
) {
    companion object {
        const val USER_PUBLIC_KEY_SIZE_BYTES = 32 * Byte.SIZE_BYTES
        const val HASH_SIZE_BYTES = 32 * Byte.SIZE_BYTES
        const val NUMBER_OF_CHUNKS_SIZE_BYTES = Long.SIZE_BYTES

        const val FILE_ID_SIZE_BYTES = USER_PUBLIC_KEY_SIZE_BYTES + HASH_SIZE_BYTES + NUMBER_OF_CHUNKS_SIZE_BYTES
    }

    init {
        assert(userPublicKey.size == USER_PUBLIC_KEY_SIZE_BYTES) { "User public key must be 256 bits" }
        assert(hash.size == HASH_SIZE_BYTES) { "File hash must be 256 bits" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileId

        if (numberOfChunks != other.numberOfChunks) return false
        if (!userPublicKey.contentEquals(other.userPublicKey)) return false
        if (!hash.contentEquals(other.hash)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = numberOfChunks.hashCode()
        result = 31 * result + userPublicKey.contentHashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }
}

class ChunkMetadata(
    val userRelativePath: String,
    val dataLength: Int,
    val updatedAt: Long,
    val index: Long,
) {
    companion object {
        const val MAX_PATH_SIZE_CHARS = 500
        const val MAX_METADATA_SIZE_BYTES =
            Int.SIZE_BYTES + MAX_PATH_SIZE_CHARS * 4 /* Max UTF-8 encoded path */ + Int.SIZE_BYTES /* dataLength */ + Long.SIZE_BYTES * 2 /* updatedAt + index */
    }

    init {
        assert(userRelativePath.length <= MAX_PATH_SIZE_CHARS) { "Path size must be less than $MAX_PATH_SIZE_CHARS characters" }
        assert(dataLength >= 0) { "Data length must be non-negative" }
        assert(updatedAt >= 0) { "Updated at must be non-negative" }
        assert(index >= 0) { "Index must be non-negative" }
    }
}