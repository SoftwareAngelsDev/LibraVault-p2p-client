package p2p.helpers

import p2p.domain.wtfs.*
import p2p.domain.wtfs.FileContentLengthBytes
import p2p.domain.wtfs.FileReservedMetadata
import p2p.domain.wtfs.PodNumber
import p2p.domain.wtfs.UnixTimestamp
import p2p.utils.mergeByteArrays
import p2p.utils.toByteArray
import java.security.MessageDigest
import kotlin.experimental.and
import kotlin.math.max

/**
 * Encodes FilePod objects into a binary format with a fixed-size byte structure.
 *
 * The encoded pod has the following structure:
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
 *
 * The encoding process fails if the total size exceeds POD_FIXED_SIZE_BYTES.
 */
class FilePodEncoder(
    private val configurationManager: ConfigurationManager,
    private val encrypt: (ByteArray) -> ByteArray,
    private val sign: (ByteArray) -> ByteArray,
) {
    companion object {
        // Header constants
        val OWNER_SIZE_BYTES = PeerPublicKey.SIZE_BYTES // RSA 4096 key size
        val NUMBER_SIZE_BYTES = PodNumber.SIZE_BYTES  // PodNumber is Int
        val TIMESTAMP_SIZE_BYTES = UnixTimestamp.SIZE_BYTES
        val SIGNATURE_SIZE_BYTES = PeerPublicKey.SIZE_BYTES // RSA 4096 signature size
        val HASH_SIZE_BYTES = Int.SIZE_BYTES
        val HEADER_SIZE_BYTES = OWNER_SIZE_BYTES + NUMBER_SIZE_BYTES + TIMESTAMP_SIZE_BYTES + SIGNATURE_SIZE_BYTES

        val RESERVED_BITS_MASK = 0x7F.toByte()
    }

    fun encode(filePod: FilePod, contents: Map<FileId, FileContent>): FilePodEncoded {
        val encodedFiles = encodeFiles(filePod, contents)
        val padding = encodePadding(encodedFiles)
        val encodedHeader = encodeHeader(filePod, encodedFiles, padding)

        val pod = mergeByteArrays(encodedHeader, encodedFiles, padding)
        return if (pod.size <= FilePod.POD_FIXED_SIZE_BYTES)
            pod
        else
            throw IllegalArgumentException("The created pod has ${pod.size} bytes which is larger than the limit of ${FilePod.POD_FIXED_SIZE_BYTES} bytes")
    }

    private fun encodePadding(encodedFiles: ByteArray): ByteArray {
        val occupiedSpace = HEADER_SIZE_BYTES + encodedFiles.size
        val paddingSize = max(FilePod.POD_FIXED_SIZE_BYTES - occupiedSpace, 0)

        // Create a zero filled byte array of the required size
        return ByteArray(paddingSize)
    }

    private fun encodeFiles(filePod: FilePod, contents: Map<FileId, FileContent>): ByteArray {
        return filePod
            .iNodes
            .sortedBy { inode ->
                val content = contents[inode.fileId]
                    ?: throw IllegalArgumentException("The file's '${inode.fileId}' content is missing")

                content.size
            }
            .map { inode ->
                val inodeMetadata = encodeINodeMetadata(inode)

                val path = encodeFilePath(inode.path, inode.isPublic)

                val createdAt = inode.createdAt.toByteArray()
                val updatedAt = inode.updatedAt.toByteArray()

                val contentHash = inode.contentHash.toByteArray()

                val contentLength = inode.contentLength.toByteArray()
                val content = contents[inode.fileId]!!.let {
                    if (it.size != inode.contentLength) {
                        throw IllegalStateException("File content length (${it.size}) doesn't match inode content length (${inode.contentLength})")
                    }

                    if (inode.isPublic) it else encrypt(it)
                }

                mergeByteArrays(
                    inodeMetadata,
                    path,
                    createdAt,
                    updatedAt,
                    contentHash,
                    contentLength,
                    content
                )
            }
            .let { mergeByteArrays(*it.toTypedArray()) }
    }

    private fun encodeINodeMetadata(inode: INode): ByteArray {
        val isPublicByte = ((if (inode.isPublic) 0x1 else 0x0) shl 7)
        val reservedBytes = (inode.reserved and RESERVED_BITS_MASK)

        return (isPublicByte + reservedBytes).toByte().toByteArray()
    }

    fun encodeFilePath(absolutePath: String, isPublic: Boolean): ByteArray {
        val bytes = absolutePath.encodeToByteArray().let { if (isPublic) it else encrypt(it) }
        val length = bytes.size.toByteArray()
        return mergeByteArrays(length, bytes)
    }

    fun encodeHeader(filePod: FilePod, encodedFiles: ByteArray, padding: ByteArray): ByteArray {
        val encodedNumber = filePod.number.toByteArray()
        val encodedUpdatedAt = filePod.updatedAt.toByteArray()

        return mergeByteArrays(
            filePod.owner.toByteArray(),
            encodedNumber,
            encodedUpdatedAt,
            sign(createSigningData(encodedNumber, encodedUpdatedAt, encodedFiles, padding)),
        )
    }

    fun calculateINodeSize(isPublic: Boolean, path: String): Int {
        val encodedPath = encodeFilePath(path, isPublic)

        return FileReservedMetadata.SIZE_BYTES + // metadata byte
                encodedPath.size + // actual path bytes
                UnixTimestamp.SIZE_BYTES * 2 + // timestamps (createdAt, updatedAt)
                FileContentLengthBytes.SIZE_BYTES + // content length
                HASH_SIZE_BYTES // content hash
    }

    fun calculateFileSize(isPublic: Boolean, path: String, content: FileContent): Int {
        return calculateINodeSize(isPublic, path) + (if (isPublic) content else encrypt(content)).size
    }
}

internal fun calculateINodeContentHash(bytes: ByteArray): Int {
    return bytes.fold(0) { acc, byte -> (acc * 31 + byte.toInt()) }
}

internal fun createSigningData(
    encodedNumber: ByteArray,
    encodedUpdatedAt: ByteArray,
    encodedFiles: ByteArray,
    padding: ByteArray,
): ByteArray = mergeByteArrays(encodedNumber, encodedUpdatedAt, encodedFiles, padding)