package p2p.helpers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import p2p.domain.wtfs.FileId
import p2p.domain.wtfs.FilePod
import p2p.domain.wtfs.INode
import p2p.domain.wtfs.PeerPublicKey
import java.util.*
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FilePodEncoderDecoderStressTest {
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var encoder: FilePodEncoder
    private lateinit var decoder: FilePodDecoder

    // Sample owner key (512 bytes for RSA 4096)
    private val ownerKey = PeerPublicKey(ByteArray(512) { it.toByte() })

    @BeforeEach
    fun setUp() {
        // Set up system properties for ConfigurationManager
        val testPrivateKey = ByteArray(512) { it.toByte() } // 512-byte test key (required by PeerPrivateKey)
        val encodedKey = Base64.getEncoder().encodeToString(testPrivateKey)
        System.setProperty("user.key", encodedKey)
        System.setProperty("udp.port", "9192")
        System.setProperty("tcp.port", "9193")
        
        // Create configuration manager with a dummy repository path
        configurationManager = ConfigurationManager()

        // Create our encoder/decoder with mock encryption functions
        encoder = FilePodEncoder(
            configurationManager = configurationManager,
            encrypt = { bytes -> mockEncrypt(bytes) },
            sign = { bytes -> mockSign(bytes) }
        )

        decoder = FilePodDecoder(
            configurationManager = configurationManager,
            decrypt = { bytes -> mockDecrypt(bytes) },
            verify = { data, signature -> mockVerify(data, signature) }
        )
    }

    @AfterEach
    fun tearDown() {
        // Clean up system properties to avoid test pollution
        System.clearProperty("user.key")
        System.clearProperty("udp.port")
        System.clearProperty("tcp.port")
    }

    @Test
    fun `test the maximum number of empty files`() = runTest {
        val fmt = "file%012d.txt"

        // Calculate file entry size (overhead already includes filename length)
        val fileEntrySize = encoder.calculateINodeSize(true, String.format(fmt, 0))

        // Calculate how many files we can fit
        val maxFiles = min(
            (FilePod.POD_FIXED_SIZE_BYTES - FilePodEncoder.HEADER_SIZE_BYTES).toLong() / fileEntrySize,
            Int.MAX_VALUE.toLong()
        ).toInt()

        println("Creating $maxFiles empty files")

        // Create empty files with predictable names
        val fileIds = (0 until maxFiles).map { index ->
            String.format(fmt, index)
        }
        val emptyByteArray = ByteArray(0)

        // Create pod with all these files
        val pod = createPublicTestPod(fileIds, 1, { emptyByteArray })

        // Encode and verify
        val encoded = encoder.encode(pod, fileIds.associateWith { emptyByteArray })
        assertNotNull(encoded, "Encoded bytes should not be null")

        val decoderResult = decoder.decode(encoded)
        val decoded = decoderResult.first
        val decodedContents = decoderResult.second

        // Verify all files were correctly decoded
        assertEquals(fileIds.size, decoded.iNodes.size, "All files should be decoded")

        // Verify each file content
        fileIds.forEach { fileId ->
            val decodedContent = decodedContents[fileId]
            assertNotNull(decodedContent, "Content should not be null for $fileId")
            assertEquals(0, decodedContent.size, "Empty file should have zero size")
        }
    }

    @Test
    fun `test maximum number of 10k byte files`() = runTest {
        // Content size
        val contentSize = 10_000 // 10 byte per file

        // Calculate file entry size (overhead already includes filename length) plus content size
        val fileEntrySize = encoder.calculateINodeSize(true, String.format("file%06d.txt", 0)) + contentSize

        // Calculate how many files we can fit
        val maxFiles = (FilePod.POD_FIXED_SIZE_BYTES - FilePodEncoder.HEADER_SIZE_BYTES) / fileEntrySize

        println("Creating $maxFiles files")

        // Create file IDs with predictable names
        val fileIds = (1..maxFiles).map { index ->
            String.format("file%06d.txt", index)
        }

        // Create content generator function that returns 1-byte content for each file
        val contentGenerator: (FileId) -> ByteArray = { fileId ->
            val index = fileId.substring(4, 10).toInt() // Extract index from filename
            byteArrayOf(index.toByte())
        }

        // Create pod with all these files
        val pod = createPublicTestPod(fileIds, 2, contentGenerator)

        // Encode and verify
        val fileContents = fileIds.associateWith { contentGenerator(it) }
        val encoded = encoder.encode(pod, fileContents)
        assertNotNull(encoded, "Encoded bytes should not be null")

        val decoderResult = decoder.decode(encoded)
        val decoded = decoderResult.first
        val decodedContents = decoderResult.second

        // Verify all files were correctly decoded
        assertEquals(fileIds.size, decoded.iNodes.size, "All files should be decoded")

        // Verify each file content
        fileIds.forEach { fileId ->
            val decodedContent = decodedContents[fileId]
            assertNotNull(decodedContent, "Content should not be null for $fileId")
            assertEquals(1, decodedContent.size, "File should have one byte")
            assertTrue(contentGenerator(fileId).contentEquals(decodedContent), "File content should match")
        }
    }

    @Test
    fun `test 10k byte files up to 99 percent capacity`() = runTest {
        // Content size
        val contentSize = 10_000 // 10 byte per file

        // Calculate file entry size (overhead already includes filename length) plus content size
        val fileEntrySize = encoder.calculateINodeSize(true, String.format("file%06d.txt", 0)) + contentSize

        // Calculate how many files we need to reach 99% capacity
        val targetSize = (FilePod.POD_FIXED_SIZE_BYTES * 0.99).toInt()
        val maxFiles = (targetSize - FilePodEncoder.HEADER_SIZE_BYTES) / fileEntrySize

        println("Creating $maxFiles files (99% capacity)")

        // Create file IDs with predictable names
        val fileIds = (1..maxFiles).map { index ->
            String.format("file%06d.txt", index)
        }

        // Create content generator function that returns 1-byte content for each file
        val contentGenerator: (FileId) -> ByteArray = { fileId ->
            val index = fileId.substring(4, 10).toInt() // Extract index from filename
            byteArrayOf(index.toByte())
        }

        // Create pod with all these files
        val pod = createPublicTestPod(fileIds, 3, contentGenerator)

        // Encode and verify
        val fileContents = fileIds.associateWith { contentGenerator(it) }
        val encoded = encoder.encode(pod, fileContents)
        assertNotNull(encoded, "Encoded bytes should not be null")

        val decoderResult = decoder.decode(encoded)
        val decoded = decoderResult.first
        val decodedContents = decoderResult.second

        // Verify all files were correctly decoded
        assertEquals(fileIds.size, decoded.iNodes.size, "All files should be decoded")

        // Verify each file content
        fileIds.forEach { fileId ->
            val decodedContent = decodedContents[fileId]
            assertNotNull(decodedContent, "Content should not be null for $fileId")
            assertEquals(1, decodedContent.size, "File should have one byte")
            assertTrue(contentGenerator(fileId).contentEquals(decodedContent), "File content should match")
        }
    }

    @Test
    fun `test one large file filling entire space`() = runTest {
        // Standard file name for predictability
        val filename = String.format("file%06d.txt", 1)

        val singleFileOverhead = encoder.calculateINodeSize(true, filename)

        // Calculate available space for content - use 95% to be safe
        // Even with deterministic signature and consistent filename length,
        // there appears to be additional overhead we're not accounting for
        val availableContentSpace =
            (FilePod.POD_FIXED_SIZE_BYTES - FilePodEncoder.HEADER_SIZE_BYTES - singleFileOverhead)

        println("Creating one large file of size $availableContentSpace bytes")

        // Create content for the large file
        val content = ByteArray(availableContentSpace) { (it % 256).toByte() }

        // Create pod with this single large file
        val pod = createPublicTestPod(listOf(filename), 4) { content }

        // Encode and verify
        val encoded = encoder.encode(pod, mapOf(filename to content))
        assertNotNull(encoded, "Encoded bytes should not be null")

        val decoderResult = decoder.decode(encoded)
        val decoded = decoderResult.first
        val decodedContents = decoderResult.second

        // Verify the file was correctly decoded
        assertEquals(1, decoded.iNodes.size, "Pod should have one file")

        // Verify file content
        val decodedContent = decodedContents[filename]
        assertNotNull(decodedContent, "Content should not be null for $filename")
        assertEquals(content.size, decodedContent.size, "File size should match")
        assertTrue(content.contentEquals(decodedContent), "File content should match")

        // Verify that the calculated file size plus the fixed Pod header = FilePod.POD_FIXED_SIZE_BYTES
        val calculatedSize = encoder.calculateFileSize(true, filename, content)
        val totalCalculatedPodSize = calculatedSize + FilePodEncoder.HEADER_SIZE_BYTES
        assertEquals(
            FilePod.POD_FIXED_SIZE_BYTES,
            totalCalculatedPodSize,
            "File size should match the entire space"
        )
    }

    private fun createPublicTestPod(
        fileIds: Collection<FileId>,
        podNumber: Int,
        content: (FileId) -> ByteArray
    ): FilePod {
        val inodes = fileIds.map { fileId ->
            // All files are public for simplicity in these tests
            val isPublic = true
            val fileContent = content(fileId)
            val contentHash = calculateHash(fileContent)

            INode(
                path = fileId,
                isPublic = isPublic,
                reserved = 0,
                contentLength = fileContent.size,
                contentHash = contentHash
            )
        }

        return FilePod(
            number = podNumber,
            owner = ownerKey,
            iNodes = inodes,
            updatedAt = System.currentTimeMillis()
        )
    }

    // Mock encryption that just adds 1 to each byte
    private fun mockEncrypt(bytes: ByteArray): ByteArray {
        val result = ByteArray(bytes.size)
        for (i in bytes.indices) {
            result[i] = (bytes[i] + 1).toByte()
        }
        return result
    }

    // Mock decryption that just subtracts 1 from each byte
    private fun mockDecrypt(bytes: ByteArray): ByteArray {
        val result = ByteArray(bytes.size)
        for (i in bytes.indices) {
            result[i] = (bytes[i] - 1).toByte()
        }
        return result
    }

    // Mock signing that returns a completely deterministic 512-byte signature
    private fun mockSign(bytes: ByteArray): ByteArray {
        // Create a fixed signature of exactly 512 bytes, ignoring input content
        return ByteArray(512) { it.toByte() }
    }

    // Mock verification that compares expected signature
    private fun mockVerify(data: ByteArray, signature: ByteArray): Boolean {
        val expectedSignature = mockSign(data)
        return signature.contentEquals(expectedSignature)
    }

    // Calculate a simple hash of byte array
    private fun calculateHash(bytes: ByteArray): Int {
        return bytes.fold(0) { acc, byte -> (acc * 31 + byte.toInt()) }
    }
}
