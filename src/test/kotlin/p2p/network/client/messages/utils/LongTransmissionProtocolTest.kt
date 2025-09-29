package p2p.network.client.messages.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LongTransmissionProtocolTest {
    
    @Test
    fun `test basic transmission with single chunk`() = runTest {
        val message = ByteArray(1000) { it.toByte() }
        val transmittedChunks = mutableMapOf<Int, ByteArray>()
        val transmissionCounts = mutableMapOf<Int, Int>()
        
        val protocol = LongTransmissionProtocol(message) { chunk, index ->
            transmittedChunks[index] = chunk
            transmissionCounts[index] = transmissionCounts.getOrDefault(index, 0) + 1
        }
        
        // Start transmission in background
        launch {
            delay(50) // Let transmission start
            protocol.confirmChunk(0)
        }
        
        protocol.process() // Should complete successfully
        assertEquals(1, transmittedChunks.size, "Should have one chunk")
        assertTrue(message.contentEquals(transmittedChunks[0]), "Chunk content should match")
    }
    
    @Test
    fun `test transmission with multiple chunks`() = runTest {
        val chunkSize = Short.MAX_VALUE.toInt()
        val message = ByteArray(chunkSize * 3 + 1000) { (it % 256).toByte() }
        val transmittedChunks = mutableMapOf<Int, ByteArray>()
        val expectedChunks = 4 // 3 full chunks + 1 partial
        
        val protocol = LongTransmissionProtocol(message) { chunk, index ->
            transmittedChunks[index] = chunk
        }
        
        // Start transmission in background and confirm chunks gradually
        launch {
            delay(50)
            for (i in 0 until expectedChunks) {
                protocol.confirmChunk(i)
                delay(20)
            }
        }
        
        protocol.process() // Should complete successfully
        assertEquals(expectedChunks, transmittedChunks.size, "Should have $expectedChunks chunks")
        
        // Verify chunk contents
        for (i in 0 until expectedChunks) {
            val startIndex = i * chunkSize
            val endIndex = kotlin.math.min(startIndex + chunkSize, message.size)
            val expectedChunk = message.sliceArray(startIndex until endIndex)
            assertTrue(expectedChunk.contentEquals(transmittedChunks[i]), "Chunk $i content should match")
        }
    }
    
    @Test
    fun `test duplicate confirmation handling`() = runTest {
        val message = ByteArray(1000) { it.toByte() }
        val transmittedChunks = mutableMapOf<Int, ByteArray>()
        val transmissionCounts = AtomicInteger(0)
        
        val protocol = LongTransmissionProtocol(message) { chunk, index ->
            transmittedChunks[index] = chunk
            transmissionCounts.incrementAndGet()
        }
        
        // Start transmission and confirm chunk multiple times
        launch {
            delay(50)
            protocol.confirmChunk(0) // First confirmation
            protocol.confirmChunk(0) // Duplicate confirmation
            protocol.confirmChunk(0) // Another duplicate
        }
        
        protocol.process() // Should complete successfully
        assertEquals(1, transmittedChunks.size, "Should have one chunk")
        
        // The chunk should only be transmitted once (or minimal times before confirmation)
        assertTrue(transmissionCounts.get() <= 5, "Should not retransmit confirmed chunk excessively")
    }
    
    @Test
    fun `test out of bounds chunk confirmation`() = runTest {
        val message = ByteArray(1000) { it.toByte() }
        val transmittedChunks = mutableMapOf<Int, ByteArray>()
        
        val protocol = LongTransmissionProtocol(message) { chunk, index ->
            transmittedChunks[index] = chunk
        }
        
        // Start transmission
        launch {
            delay(50)
            protocol.confirmChunk(-1) // Invalid negative index
            protocol.confirmChunk(999) // Invalid large index
            protocol.confirmChunk(0) // Valid confirmation
        }
        
        protocol.process() // Should complete successfully despite invalid confirmations
    }
    
    @Test
    fun `test empty message`() = runTest {
        val message = ByteArray(0)
        val transmittedChunks = mutableMapOf<Int, ByteArray>()
        
        val protocol = LongTransmissionProtocol(message) { chunk, index ->
            transmittedChunks[index] = chunk
        }
        
        protocol.process() // Empty message should complete immediately
        assertEquals(0, transmittedChunks.size, "No chunks should be transmitted for empty message")
    }
    
    @Test
    fun `test partial confirmation scenario`() = runTest {
        val chunkSize = Short.MAX_VALUE.toInt()
        val message = ByteArray(chunkSize * 3) { (it % 256).toByte() }
        val transmittedChunks = ConcurrentHashMap<Int, ByteArray>()
        val transmissionCounts = ConcurrentHashMap<Int, AtomicInteger>()
        
        val protocol = LongTransmissionProtocol(message) { chunk, index ->
            transmittedChunks[index] = chunk
            transmissionCounts.computeIfAbsent(index) { AtomicInteger(0) }.incrementAndGet()
        }
        
        // Confirm only chunks 0 and 2, leave chunk 1 unconfirmed for a while
        launch {
            delay(50)
            protocol.confirmChunk(0)
            protocol.confirmChunk(2)
            delay(200) // Let chunk 1 retransmit a few times
            protocol.confirmChunk(1)
        }
        
        protocol.process() // Should complete successfully
        assertEquals(3, transmittedChunks.size, "Should have 3 chunks")
        
        // Chunk 1 should have been retransmitted more than chunks 0 and 2
        val chunk1Transmissions = transmissionCounts[1]?.get() ?: 0
        val chunk0Transmissions = transmissionCounts[0]?.get() ?: 0
        val chunk2Transmissions = transmissionCounts[2]?.get() ?: 0
        
        assertTrue(chunk1Transmissions > chunk0Transmissions, 
            "Unconfirmed chunk should be retransmitted more often")
        assertTrue(chunk1Transmissions > chunk2Transmissions, 
            "Unconfirmed chunk should be retransmitted more often")
    }
    
    @Test
    fun `stress test with 100MB file`() = runTest {
        val fileSize = 100 * 1024 * 1024 // 100 MB
        val message = ByteArray(fileSize) { (it % 256).toByte() }
        val transmittedChunks = ConcurrentHashMap<Int, ByteArray>()
        val chunkSize = Short.MAX_VALUE.toInt()
        val expectedChunks = kotlin.math.ceil(fileSize / chunkSize.toDouble()).toInt()
        
        println("Creating stress test with ${fileSize / (1024 * 1024)}MB file ($expectedChunks chunks)")
        
        val protocol = LongTransmissionProtocol(message) { chunk, index ->
            transmittedChunks[index] = chunk
        }
        
        // Confirm chunks progressively with some delay to allow transmission
        launch {
            delay(200) // Give more time for transmission to start
            for (i in 0 until expectedChunks) {
                // Wait a bit to ensure chunk gets transmitted first
                delay(if (i % 10 == 0) 5 else 1)
                protocol.confirmChunk(i)
            }
        }
        
        val startTime = System.currentTimeMillis()
        protocol.process() // Should complete successfully
        val endTime = System.currentTimeMillis()
        
        // Note: Not all chunks may be transmitted if they're confirmed before transmission
        assertTrue(transmittedChunks.size > 0, "Should have transmitted at least some chunks")
        assertTrue(transmittedChunks.size <= expectedChunks, "Should not exceed expected chunk count")
        
        println("Transmitted ${transmittedChunks.size} chunks in ${endTime - startTime}ms")
        
        // Verify chunk contents by sampling transmitted chunks (checking every chunk would be too slow)
        val sampleIndices = transmittedChunks.keys.take(3) // Just sample first 3 transmitted chunks
        for (index in sampleIndices) {
            val startIndex = index * chunkSize
            val endIndex = kotlin.math.min(startIndex + chunkSize, message.size)
            val expectedChunk = message.sliceArray(startIndex until endIndex)
            assertTrue(expectedChunk.contentEquals(transmittedChunks[index]), 
                "Sampled chunk $index content should match")
        }
    }
    
    @Test
    fun `stress test with maximum single chunk file`() = runTest {
        val chunkSize = Short.MAX_VALUE.toInt()
        val message = ByteArray(chunkSize) { (it % 256).toByte() }
        val transmittedChunks = mutableMapOf<Int, ByteArray>()
        
        println("Testing maximum single chunk size: $chunkSize bytes")
        
        val protocol = LongTransmissionProtocol(message) { chunk, index ->
            transmittedChunks[index] = chunk
        }
        
        launch {
            delay(50)
            protocol.confirmChunk(0)
        }
        
        protocol.process() // Should complete successfully
        assertEquals(1, transmittedChunks.size, "Should have exactly one chunk")
        assertEquals(chunkSize, transmittedChunks[0]?.size, "Chunk should be exactly max size")
        assertTrue(message.contentEquals(transmittedChunks[0]), "Chunk content should match exactly")
    }
    
    @Test
    fun `stress test with one byte over maximum chunk`() = runTest {
        val chunkSize = Short.MAX_VALUE.toInt()
        val message = ByteArray(chunkSize + 1) { (it % 256).toByte() }
        val transmittedChunks = mutableMapOf<Int, ByteArray>()
        
        println("Testing one byte over maximum chunk: ${message.size} bytes (should create 2 chunks)")
        
        val protocol = LongTransmissionProtocol(message) { chunk, index ->
            transmittedChunks[index] = chunk
        }
        
        launch {
            delay(50)
            protocol.confirmChunk(0)
            protocol.confirmChunk(1)
        }
        
        protocol.process() // Should complete successfully
        assertEquals(2, transmittedChunks.size, "Should have exactly two chunks")
        assertEquals(chunkSize, transmittedChunks[0]?.size, "First chunk should be max size")
        assertEquals(1, transmittedChunks[1]?.size, "Second chunk should be 1 byte")
        
        // Verify content
        val reconstructed = transmittedChunks[0]!! + transmittedChunks[1]!!
        assertTrue(message.contentEquals(reconstructed), "Reconstructed content should match original")
    }
    
    @Test
    fun `test never confirmed chunks behavior`() = runTest {
        val message = ByteArray(1000) { it.toByte() }
        val transmittedChunks = mutableMapOf<Int, ByteArray>()
        val transmissionCounts = AtomicInteger(0)
        
        val protocol = LongTransmissionProtocol(message) { chunk, index ->
            transmittedChunks[index] = chunk
            transmissionCounts.incrementAndGet()
        }
        
        // Don't confirm any chunks - should run indefinitely, so we'll use timeout
        val result = withTimeoutOrNull(500) {
            protocol.process()
        }
        
        assertNull(result, "Should timeout when no chunks are confirmed")
        
        // Should have transmitted the chunk multiple times before timing out
        assertTrue(transmissionCounts.get() > 1, "Should retransmit before timing out")
    }
}