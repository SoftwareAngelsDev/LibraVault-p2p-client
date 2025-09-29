package p2p.helpers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import p2p.domain.wtfs.FileContent
import p2p.domain.wtfs.FileId
import p2p.domain.wtfs.FilePod
import p2p.domain.wtfs.INode
import p2p.domain.wtfs.PeerPublicKey
import p2p.utils.toByteArray
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


class FilePodEncoderDecoderTest {
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var encoder: FilePodEncoder
    private lateinit var decoder: FilePodDecoder

    // Sample owner key (512 bytes for RSA 4096)
    private val ownerKey = PeerPublicKey(ByteArray(512) { it.toByte() })

    // Sample file content
    private val sampleTextContent = "This is a test file for the FilePod encoder/decoder.\n" +
            "It contains some plain text to verify encoding works correctly."

    // Test file metadata
    private val testFiles = mutableMapOf<String, Pair<ByteArray, Boolean>>() // Map of filename to (content, isPublic)

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

        // Create test file data
        createTestFiles()
    }

    @AfterEach
    fun tearDown() {
        // Clean up system properties to avoid test pollution
        System.clearProperty("user.key")
        System.clearProperty("udp.port")
        System.clearProperty("tcp.port")
    }

    @Test
    fun `test empty pod encoding and decoding`() = runTest {
        // Create an empty pod
        val emptyPod = FilePod(
            number = 1,
            owner = ownerKey,
            iNodes = emptyList(),
            updatedAt = System.currentTimeMillis()
        )

        // Encode and decode
        val encoded = encoder.encode(emptyPod, emptyMap())
        assertNotNull(encoded, "Encoded bytes should not be null")
        assertEquals(FilePod.POD_FIXED_SIZE_BYTES, encoded.size, "Encoded pod should match fixed size")

        val (decoded, _) = decoder.decode(encoded)
        assertEquals(emptyPod.number, decoded.number, "Pod number should be preserved")
        assertEquals(0, decoded.iNodes.size, "Decoded pod should have no files")
        assertEquals(ownerKey, decoded.owner, "Owner key should be preserved")
    }

    @Test
    fun `test public file encoding and decoding`() = runTest {
        // Create a test pod with a public file
        val filename = "test_public.txt"
        val fileContent = testFiles[filename]!!.first
        val fileMap = mapOf(filename to fileContent)

        val pod = createTestPod(listOf(filename), podNumber = 2)

        // Encode and test
        testEncodingAndDecoding(pod, fileMap, listOf(filename))
    }

    @Test
    fun `test private file encoding and decoding`() = runTest {
        // Create a test pod with a private file
        val filename = "test_private.txt"
        val fileContent = testFiles[filename]!!.first
        val fileMap = mapOf(filename to fileContent)

        val pod = createTestPod(listOf(filename), podNumber = 3)

        // Encode and test
        testEncodingAndDecoding(pod, fileMap, listOf(filename))
    }

    @Test
    fun `test multiple files encoding and decoding`() = runTest {
        // Create a pod with all test files
        val fileMap = testFiles.mapValues { it.value.first }
        val pod = createTestPod(testFiles.keys.toList(), podNumber = 4)

        // Encode and test
        testEncodingAndDecoding(pod, fileMap, testFiles.keys.toList())
    }

    @Test
    fun `test size limit enforcement`() = runTest {
        // Create content that's too large
        val filename = "too_large.bin"
        val largeContent = ByteArray(FilePod.POD_FIXED_SIZE_BYTES) { 1 } // This will definitely be too large

        // Create an INode for this file
        val inode = INode(
            path = filename,
            isPublic = true,
            reserved = 0,
            contentLength = largeContent.size,
            contentHash = calculateHash(largeContent)
        )

        // Create a pod with this file
        val pod = FilePod(
            number = 5,
            owner = ownerKey,
            iNodes = listOf(inode),
            updatedAt = System.currentTimeMillis()
        )

        // This should throw an exception because the pod is too large
        assertThrows<IllegalArgumentException> {
            encoder.encode(pod, mapOf(filename to largeContent))
        }
    }

    @Test
    fun `test invalid signature`() = runTest {
        // Create a valid pod first using the first file from our test files
        val filename = testFiles.keys.first()
        val fileContent = testFiles[filename]!!.first
        val pod = createTestPod(listOf(filename), podNumber = 6)

        // Encode properly
        val encoded = encoder.encode(pod, mapOf(filename to fileContent))
        assertNotNull(encoded)

        // Tamper with the signature
        val tamperedBytes = encoded.copyOf()
        // Change a byte in the signature section
        tamperedBytes[600] = (tamperedBytes[600] + 1).toByte()

        // Decoding should fail with an exception
        assertThrows<IllegalArgumentException> {
            decoder.decode(tamperedBytes)
        }
    }

    @Test
    fun `test invalid pod size`() = runTest {
        // Create bytes with incorrect size
        val invalidSizeBytes = ByteArray(FilePod.POD_FIXED_SIZE_BYTES - 1)

        // Decoding should fail with an exception
        assertThrows<IllegalArgumentException> {
            decoder.decode(invalidSizeBytes)
        }
    }

    @Test
    fun `test mixed empty and non-empty files`() = runTest {
        // Prepare a mix of empty files and files with content
        val mixedFilenames = listOf(
            "test_empty.txt",              // Existing empty file
            "test_public.txt",             // Small text file
            "mixed_empty_1.txt",           // New empty file
            "test_binary.bin",             // Binary data
            "mixed_empty_2.txt",           // Another empty file
            "test_larger.dat",             // Larger file (10KB)
            "mixed_small.txt",             // New small file
            "mixed_medium.dat"             // New medium-sized file
        )

        // Create additional files needed for the test
        testFiles["mixed_empty_1.txt"] = ByteArray(0) to true
        testFiles["mixed_empty_2.txt"] = ByteArray(0) to true
        testFiles["mixed_small.txt"] = "Small mixed test content".encodeToByteArray() to true
        testFiles["mixed_medium.dat"] = ByteArray(5000) { (it % 128).toByte() } to true

        // Create the file content map
        val fileMap = mixedFilenames.associateWith { filename ->
            testFiles[filename]!!.first
        }

        // Create a pod with these files
        val pod = createTestPod(mixedFilenames, podNumber = 7)

        // Encode and test
        testEncodingAndDecoding(pod, fileMap, mixedFilenames)

        // Additional verification: check that empty files are indeed empty
        val emptyFiles = listOf("test_empty.txt", "mixed_empty_1.txt", "mixed_empty_2.txt")
        val decoderResult = decoder.decode(encoder.encode(pod, fileMap))
        val decodedContents = decoderResult.second

        emptyFiles.forEach { filename ->
            assertEquals(0, decodedContents[filename]!!.size, "Empty file should have size 0: $filename")
        }
    }

    private fun createTestFiles() {
        // Create a variety of test file contents in memory

        // 1. Small text file (public)
        testFiles["test_public.txt"] = sampleTextContent.encodeToByteArray() to true

        // 2. Small text file (private)
        testFiles["test_private.txt"] =
            (sampleTextContent + "\nThis file should be encrypted").encodeToByteArray() to false

        // 3. Binary file with random data
        val binaryContent = ByteArray(1024) { (it % 256).toByte() }
        testFiles["test_binary.bin"] = binaryContent to true

        // 4. Empty file
        testFiles["test_empty.txt"] = ByteArray(0) to true

        // 5. Larger file (10KB)
        val largerContent = ByteArray(10 * 1024) { (it % 256).toByte() }
        testFiles["test_larger.dat"] = largerContent to true
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

    // Mock signing that just returns a 512-byte signature based on the hash
    private fun mockSign(bytes: ByteArray): ByteArray {
        val hashBytes = calculateHash(bytes).toByteArray()

        return ByteArray(512) {
            if (it < hashBytes.size) hashBytes[it] else it.toByte()
        }
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


    /**
     * Creates a test pod with the specified filenames
     */
    private fun createTestPod(filenames: List<String>, podNumber: Int): FilePod {
        val inodes = filenames.map { filename ->
            val (content, isPublic) = testFiles[filename]!!
            val contentHash = calculateHash(content)

            INode(
                path = filename,
                isPublic = isPublic,
                reserved = 0,
                contentLength = content.size,
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

    /**
     * Tests encoding and decoding a pod and verifies the decoded content
     */
    private suspend fun testEncodingAndDecoding(
        pod: FilePod,
        fileContents: Map<FileId, FileContent>,
        filenames: List<String>
    ) {
        // Encode and decode
        val encoded = encoder.encode(pod, fileContents)
        assertNotNull(encoded, "Encoded bytes should not be null")
        if (filenames.isNotEmpty()) {
            assertEquals(FilePod.POD_FIXED_SIZE_BYTES, encoded.size, "Encoded pod should match fixed size")
        }

        val decoderResult = decoder.decode(encoded)
        val decoded = decoderResult.first
        val decodedContents = decoderResult.second
        assertEquals(pod.iNodes.size, decoded.iNodes.size, "Decoded pod should have correct number of files")

        // Verify all files were correctly decoded
        filenames.forEach { filename ->
            val decodedContent = decodedContents[filename]
            assertNotNull(decodedContent, "Decoded content should not be null for $filename")
            assertEquals(
                fileContents[filename]!!.size, decodedContent.size,
                "File size should match for $filename"
            )
            assertTrue(
                fileContents[filename]!!.contentEquals(decodedContent),
                "File content should match for $filename"
            )
        }
    }
}