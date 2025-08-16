package p2p.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import p2p.domain.Chunk
import p2p.domain.Chunk.Companion.CHUNK_SIZE_BYTES
import p2p.domain.ChunkMetadata
import p2p.domain.ChunkMetadata.Companion.MAX_METADATA_SIZE_BYTES
import p2p.domain.FileId
import p2p.domain.FileId.Companion.FILE_ID_SIZE_BYTES
import p2p.domain.FileId.Companion.HASH_SIZE_BYTES
import p2p.domain.FileId.Companion.NUMBER_OF_CHUNKS_SIZE_BYTES
import p2p.domain.FileId.Companion.USER_PUBLIC_KEY_SIZE_BYTES
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.ceil
import kotlin.math.max

class ChunkCreator(
    private val userPublicKey: ByteArray, // 256 bits
    private val chunkOutputPath: String,
    private val encrypt: (ByteArray) -> ByteArray,
    private val decrypt: (ByteArray) -> ByteArray,
) {
    class ChunkCreationResult(
        val chunks: List<Chunk>,
        val progressPercentage: Short
    )

    private class NetworkChunk(
        fileId: FileId,
        metadata: ChunkMetadata,
        path: String,
        private val encrypt: (ByteArray) -> ByteArray,
        private val decrypt: (ByteArray) -> ByteArray,
    ) : Chunk(fileId, metadata, path) {
        override fun getTransmittingData(): ByteArray {
            return File(path).readBytes()
        }

        override fun getChunkData(): ByteArray {
            val finalChunkData = getTransmittingData()
            val headerSize = FILE_ID_SIZE_BYTES + metadata.toByteArray(encrypt).size

            if (finalChunkData.size < headerSize) {
                throw IllegalArgumentException("Chunk is of invalid size: ${finalChunkData.size} bytes")
            }

            return decrypt(finalChunkData.copyOfRange(headerSize, finalChunkData.size))
        }
    }

    suspend fun mergeFileFromChunks(chunkFiles: List<File>): InputStream = withContext(Dispatchers.IO) {
        val chunks = chunkFiles.map { decodeChunk(it) }.sortedBy { it.metadata.index }

        // Confirm that all chunks have the same fileId
        val fileId = chunks.first().fileId
        chunks.forEach { chunk ->
            if (chunk.fileId != fileId) {
                throw IllegalArgumentException("All chunks must have the same fileId")
            }
        }

        // Confirm that all chunk indexes are present
        val chunkIndexes = chunks.mapTo(HashSet()) { it.metadata.index }
        for (index in 0 until fileId.numberOfChunks) {
            if (!chunkIndexes.contains(index)) {
                throw IllegalArgumentException("Chunk $index is missing")
            }
        }

        object : InputStream() {
            var data: ByteArray? = null
            var totalBytesRead = 0
            var chunkIndex: Int = -1
            var cursor: Int = 0

            override fun read(): Int {
                if (data == null || cursor >= data!!.size) {
                    if (chunkIndex >= chunks.lastIndex) {
                        return -1
                    }

                    data = chunks[++chunkIndex].getChunkData()
                    cursor = 0
                }

                totalBytesRead++
                return (data!![cursor++]).toUByte().toInt()
            }

            override fun readAllBytes(): ByteArray {
                val data = mutableListOf<Byte>()

                while (available() > 0) {
                    data.add(read().toByte())
                }

                return data.toByteArray()
            }

            override fun available(): Int {
                return max(0, chunks.sumOf { it.metadata.dataLength } - totalBytesRead)
            }
        }
    }

    suspend fun splitFileIntoChunks(file: File, relativePath: String): ChunkCreationResult =
        withContext(Dispatchers.IO) {
            val numberOfChunks = ceil(file.length().toDouble() / CHUNK_SIZE_BYTES).toLong()
            val fileId = calculateFileId(file, numberOfChunks)

            val chunks = mutableListOf<Chunk>()
            var currentProgress: Short = 0

            file.inputStream().buffered().use { input ->
                for (chunkIndex in 0 until numberOfChunks) {
                    val chunkStartIndex = chunkIndex * CHUNK_SIZE_BYTES
                    val chunkEndIndex = minOf(file.length(), (chunkIndex + 1) * CHUNK_SIZE_BYTES)
                    val chunkSize = (chunkEndIndex - chunkStartIndex).toInt()

                    val metadata = ChunkMetadata(
                        userRelativePath = relativePath,
                        dataLength = chunkSize,
                        updatedAt = System.currentTimeMillis(),
                        index = chunkIndex,
                    )

                    assert(chunkSize <= CHUNK_SIZE_BYTES) { "Chunk size must be less than $CHUNK_SIZE_BYTES bytes" }

                    val chunkFileData = ByteArray(chunkSize)

                    input.read(chunkFileData)

                    val chunkEncryptedFileData = encrypt(chunkFileData)
                    val chunkOutputFileName = "$chunkOutputPath/$fileId-$chunkIndex"

                    val finalChunkData = encodeChunk(fileId, metadata, chunkEncryptedFileData)

                    val chunkFile = File(chunkOutputFileName)
                    chunkFile.writeBytes(finalChunkData)
                    chunks.add(
                        NetworkChunk(
                            fileId = fileId,
                            metadata = metadata,
                            path = chunkOutputFileName,
                            encrypt = encrypt,
                            decrypt = decrypt,
                        )
                    )

                    // Update progress after each chunk (0-100)
                    currentProgress = ((chunkIndex + 1) * 100 / numberOfChunks).toShort()
                }
            }

            ChunkCreationResult(chunks, currentProgress)
        }

    private fun encodeChunk(
        id: FileId,
        metadata: ChunkMetadata,
        data: ByteArray
    ): ByteArray {
        val fileIdBytes = id.toByteArray()
        val metadataBytes = metadata.toByteArray(encrypt)

        // Prepend fileId and metadata to data
        val finalChunkData = ByteArray(FILE_ID_SIZE_BYTES + metadataBytes.size + data.size)
        System.arraycopy(fileIdBytes, 0, finalChunkData, 0, FILE_ID_SIZE_BYTES)
        System.arraycopy(metadataBytes, 0, finalChunkData, FILE_ID_SIZE_BYTES, metadataBytes.size)
        System.arraycopy(data, 0, finalChunkData, FILE_ID_SIZE_BYTES + metadataBytes.size, data.size)

        return finalChunkData
    }

    private fun decodeChunk(chunkFile: File): Chunk {
        // Calculate max possible header size
        val maxHeaderSize = FILE_ID_SIZE_BYTES + MAX_METADATA_SIZE_BYTES

        // Read only the bytes needed for the header
        val fileLength = chunkFile.length()
        val bytesToRead = kotlin.math.min(maxHeaderSize.toLong(), fileLength)
        val finalChunkData = chunkFile.inputStream().use { it.readNBytes(bytesToRead.toInt()) }

        val fileId = fileIdFromByteArray(finalChunkData)
        val metadata = chunkMetadataFromByteArray(finalChunkData, decrypt)

        val path = chunkFile.absolutePath

        return NetworkChunk(
            fileId = fileId,
            metadata = metadata,
            path = path,
            encrypt = encrypt,
            decrypt = decrypt,
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun calculateFileId(file: File, numberOfChunks: Long): FileId = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192) // 8KB buffer
        val inputStream = file.inputStream()

        try {
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }

            val hash = digest.digest()

            FileId(userPublicKey, hash, numberOfChunks)
        } finally {
            inputStream.close()
        }
    }
}


private fun ChunkMetadata.toByteArray(encrypt: (ByteArray) -> ByteArray): ByteArray {
    val encryptedPath = encrypt(userRelativePath.toByteArray())

    val pathLength = encryptedPath.size

    // Create byte array with appropriate size for all fields
    val result = ByteArray(Int.SIZE_BYTES + pathLength + Int.SIZE_BYTES + Long.SIZE_BYTES * 2)
    var offset = 0

    // Write path length
    val pathLengthBytes = pathLength.toByteArray()
    System.arraycopy(pathLengthBytes, 0, result, offset, pathLengthBytes.size)
    offset += pathLengthBytes.size

    // Write path bytes
    System.arraycopy(encryptedPath, 0, result, offset, pathLength)
    offset += pathLength

    // Write data length
    val dataLengthBytes = dataLength.toByteArray()
    System.arraycopy(dataLengthBytes, 0, result, offset, dataLengthBytes.size)
    offset += dataLengthBytes.size

    // Write updatedAt
    val updatedAtBytes = updatedAt.toByteArray()
    System.arraycopy(updatedAtBytes, 0, result, offset, updatedAtBytes.size)
    offset += updatedAtBytes.size

    // Write index
    val indexBytes = index.toByteArray()
    System.arraycopy(indexBytes, 0, result, offset, indexBytes.size)

    return result
}

private fun chunkMetadataFromByteArray(finalChunkData: ByteArray, decrypt: (ByteArray) -> ByteArray): ChunkMetadata {
    var offset = FILE_ID_SIZE_BYTES

    // Read path length (4 bytes)
    val pathLengthBytes = ByteArray(Int.SIZE_BYTES)
    System.arraycopy(finalChunkData, offset, pathLengthBytes, 0, pathLengthBytes.size)
    val pathLength = pathLengthBytes.toInt()
    offset += pathLengthBytes.size

    // Validate path length
    if (pathLength <= 0) {
        throw IllegalArgumentException("Invalid path length: $pathLength")
    }

    // Read path bytes
    val path = ByteArray(pathLength)
    System.arraycopy(finalChunkData, offset, path, 0, path.size)
    offset += path.size

    // Read data length
    val dataLengthBytes = ByteArray(Int.SIZE_BYTES)
    System.arraycopy(finalChunkData, offset, dataLengthBytes, 0, dataLengthBytes.size)
    val dataLength = dataLengthBytes.toInt()
    offset += dataLengthBytes.size

    // Read updatedAt
    val updatedAtBytes = ByteArray(Long.SIZE_BYTES)
    System.arraycopy(finalChunkData, offset, updatedAtBytes, 0, updatedAtBytes.size)
    val updatedAt = updatedAtBytes.toLong()
    offset += updatedAtBytes.size

    // Read index
    val indexBytes = ByteArray(Long.SIZE_BYTES)
    System.arraycopy(finalChunkData, offset, indexBytes, 0, indexBytes.size)
    val index = indexBytes.toLong()

    return ChunkMetadata(String(decrypt(path)), dataLength, updatedAt, index)
}

private fun fileIdFromByteArray(finalChunkData: ByteArray): FileId {
    var offset = 0

    // Read user public key (256 bits)
    val userPublicKey = ByteArray(USER_PUBLIC_KEY_SIZE_BYTES)
    System.arraycopy(finalChunkData, offset, userPublicKey, 0, USER_PUBLIC_KEY_SIZE_BYTES)
    offset += userPublicKey.size

    // Read file hash (256 bits)
    val hash = ByteArray(HASH_SIZE_BYTES)
    System.arraycopy(finalChunkData, offset, hash, 0, HASH_SIZE_BYTES)
    offset += hash.size

    // Read number of chunks (8 bytes)
    val numberOfChunksBytes = ByteArray(NUMBER_OF_CHUNKS_SIZE_BYTES)
    System.arraycopy(finalChunkData, offset, numberOfChunksBytes, 0, NUMBER_OF_CHUNKS_SIZE_BYTES)
    val numberOfChunks = numberOfChunksBytes.toLong()

    return FileId(userPublicKey, hash, numberOfChunks)
}

private fun FileId.toByteArray(): ByteArray {
    val id = ByteArray(USER_PUBLIC_KEY_SIZE_BYTES + HASH_SIZE_BYTES + NUMBER_OF_CHUNKS_SIZE_BYTES)

    // Concat user public key and file hash and number of chunks
    System.arraycopy(userPublicKey, 0, id, 0, USER_PUBLIC_KEY_SIZE_BYTES)
    System.arraycopy(hash, 0, id, USER_PUBLIC_KEY_SIZE_BYTES, HASH_SIZE_BYTES)
    System.arraycopy(
        numberOfChunks.toByteArray(),
        0,
        id,
        USER_PUBLIC_KEY_SIZE_BYTES + HASH_SIZE_BYTES,
        NUMBER_OF_CHUNKS_SIZE_BYTES
    )

    return id
}