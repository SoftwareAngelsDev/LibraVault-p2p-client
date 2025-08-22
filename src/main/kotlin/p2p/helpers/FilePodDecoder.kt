package p2p.helpers

import p2p.domain.wtfs.FileContent
import p2p.domain.wtfs.FileContentHash
import p2p.domain.wtfs.FileContentLengthBytes
import p2p.domain.wtfs.FileId
import p2p.domain.wtfs.FilePod
import p2p.domain.wtfs.INode
import p2p.helpers.FilePodEncoder.Companion.NUMBER_SIZE_BYTES
import p2p.helpers.FilePodEncoder.Companion.OWNER_SIZE_BYTES
import p2p.helpers.FilePodEncoder.Companion.RESERVED_BITS_MASK
import p2p.helpers.FilePodEncoder.Companion.SIGNATURE_SIZE_BYTES
import p2p.helpers.FilePodEncoder.Companion.TIMESTAMP_SIZE_BYTES
import p2p.utils.mergeByteArrays
import p2p.utils.toInt
import p2p.utils.toLong
import java.util.*
import kotlin.experimental.and

/**
 * Decodes binary FilePod format back into FilePod objects.
 *
 * The binary format has the following structure:
 *
 * 1. Header:
 *    - Owner (512 bytes - RSA 4096 key)
 *    - Number (8 bytes - Long)
 *    - UpdatedAt timestamp (8 bytes - Long)
 *    - Signature (512 bytes - RSA 4096 signature of number + updatedAt + encodedFiles)
 *
 * 2. Encoded Files Section (multiple file entries concatenated):
 *    Each file entry contains:
 *    - Metadata (1 byte) - bit 7: public/private flag, bits 1-6: reserved
 *    - Path (4 bytes length prefix + variable bytes, encrypted if private)
 *    - CreatedAt timestamp (8 bytes - Long)
 *    - UpdatedAt timestamp (8 bytes - Long)
 *    - ContentHash (4 bytes - Int)
 *    - ContentLength (4 bytes - Int)
 *    - Content (variable - file content, encrypted if private)
 *
 * 3. Padding:
 *    - Zero-filled bytes to ensure total pod size equals POD_FIXED_SIZE_BYTES
 */
class FilePodDecoder(
    private val configurationManager: ConfigurationManager,
    private val decrypt: (ByteArray) -> ByteArray,
    private val verify: (ByteArray, ByteArray) -> Boolean
) {
    companion object {
        // Offsets
        private val OWNER_OFFSET = 0
        private val NUMBER_OFFSET = OWNER_SIZE_BYTES
        private val UPDATED_AT_OFFSET = NUMBER_OFFSET + NUMBER_SIZE_BYTES
        private val SIGNATURE_OFFSET = UPDATED_AT_OFFSET + TIMESTAMP_SIZE_BYTES
        private val FILES_SECTION_OFFSET = SIGNATURE_OFFSET + SIGNATURE_SIZE_BYTES

        // File entry constants
        private val PATH_LENGTH_SIZE_BYTES = Int.SIZE_BYTES
        private val CONTENT_HASH_SIZE_BYTES = FileContentHash.SIZE_BYTES
        private val CONTENT_LENGTH_SIZE_BYTES = FileContentLengthBytes.SIZE_BYTES

        private val FILE_RESERVED_BITS_BYTES = 1
        private val FILE_ELEMENT_FIRST_BYTES = FILE_RESERVED_BITS_BYTES + PATH_LENGTH_SIZE_BYTES

        private val EMPTY_BYTE_ARRAY = ByteArray(0)
    }

    fun decode(bytes: ByteArray): Pair<FilePod, Map<FileId, FileContent>> {
        if (bytes.size != FilePod.POD_FIXED_SIZE_BYTES) {
            throw IllegalArgumentException("Invalid pod size: ${bytes.size}")
        }

        // Extract header components
        val owner = bytes.sliceArray(OWNER_OFFSET until NUMBER_OFFSET)
        val numberBytes = bytes.sliceArray(NUMBER_OFFSET until UPDATED_AT_OFFSET)
        val updatedAtBytes = bytes.sliceArray(UPDATED_AT_OFFSET until SIGNATURE_OFFSET)
        val signature = bytes.sliceArray(SIGNATURE_OFFSET until FILES_SECTION_OFFSET)

        // Convert bytes to primitive types
        val number = numberBytes.toInt()
        val updatedAt = updatedAtBytes.toLong()

        // Extract encoded files section (everything after header and before padding)
        val encodedFiles = bytes.sliceArray(FILES_SECTION_OFFSET until bytes.size)

        // Verify signature
        val dataToVerify = createSigningData(
            numberBytes,
            updatedAtBytes,
            encodedFiles
        )

        if (!verify(dataToVerify, signature)) {
            throw IllegalArgumentException("Invalid signature")
        }

        // Decode files
        val (iNodes, content) = decodeFiles(encodedFiles)

        return FilePod(
            number = number,
            owner = owner,
            iNodes = iNodes,
            updatedAt = updatedAt
        ) to content
    }

    private fun createSigningData(
        encodedNumber: ByteArray,
        encodedUpdatedAt: ByteArray,
        encodedFiles: ByteArray
    ): ByteArray = mergeByteArrays(encodedNumber, encodedUpdatedAt, encodedFiles)

    private fun decodeFiles(encodedFiles: ByteArray): Pair<Collection<INode>, Map<FileId, FileContent>> {
        val iNodes = LinkedList<INode>()
        val contents = HashMap<String, FileContent>()
        var position = 0

        while (position < encodedFiles.size) {
            if (endOfFileListReached(position, encodedFiles)) {
                break
            }

            // Read metadata byte
            val metadata = encodedFiles[position]
            position++

            val isPublic = (metadata.toUByte().toInt() shr 7) == 1
            val reserved = (metadata and RESERVED_BITS_MASK)

            // Read path
            val pathLengthBytes = encodedFiles.sliceArray(position until position + PATH_LENGTH_SIZE_BYTES)
            position += PATH_LENGTH_SIZE_BYTES
            val pathLength = pathLengthBytes.toInt()

            val encodedPath = encodedFiles.sliceArray(position until position + pathLength)
            position += pathLength

            val path = if (isPublic) {
                String(encodedPath)
            } else {
                String(decrypt(encodedPath))
            }

            // Read timestamps
            val createdAt = encodedFiles.sliceArray(position until position + TIMESTAMP_SIZE_BYTES).toLong()
            position += TIMESTAMP_SIZE_BYTES
            val updatedAt = encodedFiles.sliceArray(position until position + TIMESTAMP_SIZE_BYTES).toLong()
            position += TIMESTAMP_SIZE_BYTES

            // Read content metadata
            val contentHash = encodedFiles.sliceArray(position until position + CONTENT_HASH_SIZE_BYTES).toInt()
            position += CONTENT_HASH_SIZE_BYTES
            val contentLength = encodedFiles.sliceArray(position until position + CONTENT_LENGTH_SIZE_BYTES).toInt()
            position += CONTENT_LENGTH_SIZE_BYTES

            // Read content
            val content = if (contentLength > 0) {
                encodedFiles.sliceArray(position until position + contentLength)
            } else {
                EMPTY_BYTE_ARRAY
            }
            position += contentLength

            val decodedContent = if (isPublic || content.isEmpty()) content else decrypt(content)

            // Create INode
            iNodes.add(
                INode(
                    path = path,
                    isPublic = isPublic,
                    reserved = reserved,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    contentLength = contentLength,
                    contentHash = contentHash
                )
            )

            contents[path] = decodedContent
        }

        return iNodes to contents
    }

    private fun endOfFileListReached(position: Int, encodedFiles: ByteArray): Boolean {
        if (FILE_ELEMENT_FIRST_BYTES + position >= encodedFiles.size)
            return true // There's no room for more files, what's left is just padding

        val reservedBitsPlusPathLength = encodedFiles.sliceArray(position until position + FILE_ELEMENT_FIRST_BYTES)
        val isAllPadding =
            reservedBitsPlusPathLength.all { it == 0.toByte() } // No file can have a zero size path length

        return isAllPadding
    }
}