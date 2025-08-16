package p2p.utils

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import p2p.domain.Chunk
import p2p.helpers.ChunkCreator
import p2p.helpers.ChunkCreator.Companion.DEFAULT_CHUNK_SIZE_BYTES
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*
import kotlin.math.ceil

class ChunkCreatorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var userPublicKey: ByteArray
    private lateinit var chunkOutputPath: String
    private lateinit var chunkCreator: ChunkCreator

    // Simple mock encryption/decryption functions for testing
    private val encrypt: (ByteArray) -> ByteArray = { data -> data }
    private val decrypt: (ByteArray) -> ByteArray = { data -> data }

    @BeforeEach
    fun setup() {
        // Create a mock user public key (256 bits = 32 bytes)
        userPublicKey = ByteArray(32) // 32 bytes = 256 bits
        Random().nextBytes(userPublicKey)

        // Create temp directory for chunk output
        chunkOutputPath = tempDir.resolve("chunks").toString()
        Files.createDirectories(Path.of(chunkOutputPath))

        // Initialize ChunkCreator with mock functions
        chunkCreator = ChunkCreator(userPublicKey, chunkOutputPath, encrypt, decrypt)
    }

    @AfterEach
    fun tearDown() {
        // Clean up temp files
        File(chunkOutputPath).deleteRecursively()
    }

    @Test
    fun `test create chunks for small file`() = runBlocking<Unit> {
        // Create a small test file (2KB)
        val fileSize = convertKBToBytes(2)
        testFileChunkingAndMerging(fileSize, "small")
    }

    @Test
    fun `test create chunks for file exactly at chunk size boundary`() = runBlocking<Unit> {
        // Create a file exactly at chunk boundary (100MB)
        val fileSize = DEFAULT_CHUNK_SIZE_BYTES.toLong()
        testFileChunkingAndMerging(fileSize, "boundary")
    }

    @Test
    fun `test chunk calculation for empty file`() = runBlocking<Unit> {
        // Create an empty test file (0 bytes)
        val fileSize = 0L
        testFileChunkingAndMerging(fileSize, "empty")
    }

    @Test
    fun `test create multiple chunks for medium sized file`() = runBlocking<Unit> {
        // Create a medium sized file (210MB)
        val fileSize = (DEFAULT_CHUNK_SIZE_BYTES * 2) + (DEFAULT_CHUNK_SIZE_BYTES / 10)
        testFileChunkingAndMerging(fileSize.toLong(), "medium")
    }

    @Test
    fun `test file ID calculation`() = runBlocking<Unit> {
        // Create test file
        val fileSize = 1024
        val testFile = createTestFile(fileSize.toLong())
        val relativePath = "test/file_id_test.txt"

        // Calculate expected hash
        val expectedHash = calculateFileHash(testFile)
        val expectedNumberOfChunks = ceil(fileSize.toDouble() / DEFAULT_CHUNK_SIZE_BYTES).toLong()

        // Split file into chunks to get the fileId
        val result = chunkCreator.splitFileIntoChunks(testFile, relativePath)
        val fileId = result.chunks.first().fileId

        // Verify file ID components
        assertTrue(fileId.userPublicKey.contentEquals(userPublicKey))
        assertTrue(fileId.hash.contentEquals(expectedHash))
        assertEquals(expectedNumberOfChunks, fileId.numberOfChunks)
    }

    @Test
    fun `test chunk calculation for very large file`() = runBlocking<Unit> {
        // Create an actual 10GB test file
        val fileSize = convertGBToBytes(100)
        testFileChunkingAndMerging(fileSize, "very_large")
    }

    @Test
    fun `test merge chunks for small file`() = runBlocking<Unit> {
        // Create a small test file
        val fileSize = convertKBToBytes(5)
        val originalFile = createTestFile(fileSize.toLong())
        val relativePath = "test/merge_small.txt"

        // Split file into chunks
        val chunkResult = chunkCreator.splitFileIntoChunks(originalFile, relativePath)
        val chunkFiles = chunkResult.chunks.map { chunk -> File(chunk.path) }

        // Merge chunks back
        val mergedStream = chunkCreator.mergeFileFromChunks(chunkFiles)

        // Create output file for merged content
        val mergedOutputFile = tempDir.resolve("merged_output_small.bin").toFile()

        // Stream the merged content to a file instead of loading into memory
        mergedOutputFile.outputStream().use { output ->
            mergedStream.use { input ->
                input.copyTo(output)
            }
        }

        // Compare files byte by byte using streams instead of loading entire content
        assertTrue(compareFilesByteByByte(originalFile, mergedOutputFile))
    }

    @Test
    fun `test merge multiple chunks`() = runBlocking<Unit> {
        // Define explicit chunk size for this test
        val chunkSizeBytes = convertMBToBytes(100).toInt()
        
        // Create a file size that ensures multiple chunks (11 chunks total)
        // 10 complete chunks plus 1 partial chunk
        val completeChunks = 10
        val fileSize = (chunkSizeBytes.toLong() * completeChunks) + (chunkSizeBytes / 2)
        
        testFileChunkingAndMerging(fileSize, "multiple_chunks", chunkSizeBytes = chunkSizeBytes)
    }

    @Test
    fun `test merge multiple chunks with different chunk sizes`() = runBlocking<Unit> {
        // Define smaller chunk size for this test
        val chunkSizeBytes = convertMBToBytes(10).toInt()
        
        // Create a file that will generate 110 chunks (109 complete + 1 partial)
        val completeChunks = 109
        val fileSize = (chunkSizeBytes.toLong() * completeChunks) + (chunkSizeBytes / 2)
        
        testFileChunkingAndMerging(fileSize, "multiple_chunks", chunkSizeBytes = chunkSizeBytes)
    }

    @Test
    fun `test merge chunks in wrong order`() = runBlocking<Unit> {
        // Create a file that will be split into multiple chunks
        val fileSize = (DEFAULT_CHUNK_SIZE_BYTES + convertMBToBytes(1)) * 2L

        // Define a chunk ordering function that sorts chunks in reverse order
        val reverseOrderingFn: (List<Chunk>) -> List<Chunk> = { chunks ->
            chunks.sortedByDescending { chunk -> chunk.metadata.index }
        }

        testFileChunkingAndMerging(fileSize, "wrong_order", reverseOrderingFn)
    }

    @Test
    fun `test merge with missing chunks throws exception`() = runBlocking<Unit> {
        // Create a file that will be split into multiple chunks (201MB)
        val fileSize = DEFAULT_CHUNK_SIZE_BYTES * 2 + 1024 * 1024L

        // Test with missing chunk parameter set to false and expected error message
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                testFileChunkingAndMerging(
                    fileSize,
                    "missing_chunks",
                    { chunks -> chunks.dropLast(1) },
                )
            }
        }

        assertTrue(exception.message!!.contains("missing", true))
    }

    // Helper method to create test files of specified size
    private fun createTestFile(sizeInBytes: Long): File {
        val file = tempDir.resolve("test_file_${sizeInBytes}.bin").toFile()

        // For small files, write the actual content
        if (sizeInBytes < convertMBToBytes(10)) { // Less than 10MB
            val buffer = ByteArray(1024)
            Random().nextBytes(buffer)

            file.outputStream().buffered().use { out ->
                var remaining = sizeInBytes
                while (remaining > 0) {
                    val writeSize = minOf(buffer.size.toLong(), remaining).toInt()
                    out.write(buffer, 0, writeSize)
                    remaining -= writeSize
                }
            }
        } else {
            // For large files, use sparse file feature
            file.createNewFile()
            var randomAccessFile = java.io.RandomAccessFile(file, "rw")
            randomAccessFile.setLength(sizeInBytes)
            randomAccessFile.close()

            // Write some random data at the beginning, middle and end
            val random = Random()
            val buffer = ByteArray(1024)

            file.outputStream().buffered().use { out ->
                // Beginning
                random.nextBytes(buffer)
                out.write(buffer)

                // Seek to middle and write
                out.close()
            }

            val middle = sizeInBytes / 2
            val end = sizeInBytes - 1024

            randomAccessFile = java.io.RandomAccessFile(file, "rw")

            // Middle
            randomAccessFile.seek(middle)
            random.nextBytes(buffer)
            randomAccessFile.write(buffer)

            // End
            if (end > middle + 1024) {
                randomAccessFile.seek(end)
                random.nextBytes(buffer)
                randomAccessFile.write(buffer)
            }

            randomAccessFile.close()
        }

        return file
    }

    // Helper method to calculate SHA-256 hash of a file
    private fun calculateFileHash(file: File): ByteArray {
        // Fixed size hash to ensure it's always 256 bits (32 bytes)
        val fixedHash = ByteArray(32) // 32 bytes = 256 bits

        // Generate a real hash first
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192) // 8KB buffer

        file.inputStream().use { input ->
            var read: Int
            while (input.read(buffer).also { bytesRead -> read = bytesRead } != -1) {
                digest.update(buffer, 0, read)
            }
        }

        // Copy the actual digest into our fixed-size array
        val actualDigest = digest.digest()
        System.arraycopy(actualDigest, 0, fixedHash, 0, fixedHash.size.coerceAtMost(actualDigest.size))

        return fixedHash
    }

    // Helper method to compare files byte by byte without loading them entirely into memory
    private fun compareFilesByteByByte(file1: File, file2: File): Boolean {
        if (file1.length() != file2.length()) {
            return false
        }

        file1.inputStream().buffered().use { input1 ->
            file2.inputStream().buffered().use { input2 ->
                return compareStreamsByteByByte(input1, input2)
            }
        }
    }

    // Helper method to compare a file with an input stream directly
    private fun compareFileWithStream(file: File, stream: InputStream): Boolean {
        file.inputStream().buffered().use { fileStream ->
            return compareStreamsByteByByte(fileStream, stream)
        }
    }

    // Helper method to compare two input streams byte by byte
    private fun compareStreamsByteByByte(input1: InputStream, input2: InputStream): Boolean {
        val buffer1 = ByteArray(8192) // 8KB buffer
        val buffer2 = ByteArray(8192)
        var bytesRead1: Int
        var bytesRead2: Int

        while (true) {
            bytesRead1 = input1.read(buffer1)
            bytesRead2 = input2.read(buffer2)

            if (bytesRead1 != bytesRead2) {
                return false
            }

            if (bytesRead1 == -1) {
                break // End of both streams reached
            }

            for (i in 0 until bytesRead1) {
                if (buffer1[i] != buffer2[i]) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * Unified helper method to test file chunking and merging for various file sizes
     * @param fileSize Size of the test file in bytes
     * @param fileDescription Description of the file size (e.g., "empty", "small", "large")
     * @param chunkOrderingFn Optional function to reorder chunks before merging (for testing wrong order)
     */
    private suspend fun testFileChunkingAndMerging(
        fileSize: Long,
        fileDescription: String,
        chunkOrderingFn: ((List<Chunk>) -> List<Chunk>)? = null,
        chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES
    ) {
        // Check if we have enough disk space
        val chunkSize = chunkSizeBytes.toLong()
        val requiredSpace = fileSize * 2 // Original file + chunks
        val freeSpace = File(tempDir.toString()).freeSpace

        if (freeSpace < requiredSpace) {
            println("Skipping $fileDescription file test due to insufficient disk space")
            return
        }

        // Create the test file
        println("Creating $fileDescription test file ($fileSize bytes)...")
        val testFile = createTestFile(fileSize)
        val relativePath = "test/${fileDescription}_file.bin"

        // Calculate expected chunks
        val expectedNumberOfChunks = ceil(fileSize.toDouble() / chunkSize).toLong()
        println("Expected chunks: $expectedNumberOfChunks")

        // Split into chunks
        println("Processing $fileDescription file into chunks...")
        val result = chunkCreator.splitFileIntoChunks(testFile, relativePath, chunkSizeBytes)

        // Verify number of chunks
        assertEquals(
            expectedNumberOfChunks, result.chunks.size.toLong(),
            "Should have correct number of chunks for $fileDescription file"
        )
        // For empty files, progress will be 0 (as there are 0 chunks)
        val expectedProgress = if (fileSize == 0L) 0.toShort() else 100.toShort()
        assertEquals(
            expectedProgress, result.progressPercentage,
            "Progress should be ${expectedProgress}% for $fileDescription file"
        )

        // Verify chunk indexes
        val indexes = result.chunks.map { chunk -> chunk.metadata.index }.sorted()
        for (i in 0 until expectedNumberOfChunks) {
            assertEquals(i, indexes[i.toInt()], "Chunk index should be sequential")
        }

        // Verify last chunk size if not exactly at boundary
        val lastChunkExpectedSize = fileSize % chunkSize
        if (lastChunkExpectedSize > 0) {
            val lastChunk = result.chunks.maxByOrNull { chunk -> chunk.metadata.index }!!
            assertEquals(
                lastChunkExpectedSize, lastChunk.getChunkData().size.toLong(),
                "Last chunk should have correct size"
            )
        }

        println("Successfully processed $fileDescription file into ${result.chunks.size} chunks")

        // Apply ordering function if provided
        val chunksToMerge = chunkOrderingFn?.invoke(result.chunks) ?: result.chunks
        val chunkFiles = chunksToMerge.map { chunk -> File(chunk.path) }

        // Skip merging test for empty files since they produce no chunks
        if (fileSize == 0L) {
            println("Skipping merge test for empty file (no chunks to merge)")
            return
        }

        // Now test merging
        println("Testing merge of $fileDescription file chunks...")
        val mergedStream = chunkCreator.mergeFileFromChunks(chunkFiles)

        println("Comparing original and reconstructed $fileDescription files...")
        assertTrue(
            compareFileWithStream(testFile, mergedStream),
            "Merged stream should be identical to original $fileDescription file"
        )

        println("Successfully verified $fileDescription file chunk and merge process")
    }
}