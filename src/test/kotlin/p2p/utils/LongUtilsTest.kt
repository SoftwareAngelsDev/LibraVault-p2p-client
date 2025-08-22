package p2p.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import kotlin.random.Random

class LongUtilsTest {

    @Test
    fun `test Long toByteArray`() {
        // Test zero
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0), 0L.toByteArray())
        
        // Test positive value
        val expected1 = ByteBuffer.allocate(8).putLong(42L).array()
        assertArrayEquals(expected1, 42L.toByteArray())
        
        // Test negative value
        val expected2 = ByteBuffer.allocate(8).putLong(-1L).array()
        assertArrayEquals(expected2, (-1L).toByteArray())
        
        // Test maximum long value
        val expected3 = ByteBuffer.allocate(8).putLong(Long.MAX_VALUE).array()
        assertArrayEquals(expected3, Long.MAX_VALUE.toByteArray())
        
        // Test minimum long value
        val expected4 = ByteBuffer.allocate(8).putLong(Long.MIN_VALUE).array()
        assertArrayEquals(expected4, Long.MIN_VALUE.toByteArray())
    }

    @Test
    fun `test ByteArray toLong`() {
        // Test zero
        assertEquals(0L, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0).toLong())
        
        // Test positive value
        val bytes1 = ByteBuffer.allocate(8).putLong(42L).array()
        assertEquals(42L, bytes1.toLong())
        
        // Test negative value
        val bytes2 = ByteBuffer.allocate(8).putLong(-1L).array()
        assertEquals(-1L, bytes2.toLong())
        
        // Test maximum long value
        val bytes3 = ByteBuffer.allocate(8).putLong(Long.MAX_VALUE).array()
        assertEquals(Long.MAX_VALUE, bytes3.toLong())
        
        // Test minimum long value
        val bytes4 = ByteBuffer.allocate(8).putLong(Long.MIN_VALUE).array()
        assertEquals(Long.MIN_VALUE, bytes4.toLong())
    }

    @Test
    fun `test ByteArray toLong with invalid array size`() {
        // Test with array that's too small
        val tooSmall = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        assertThrows<IllegalArgumentException> {
            tooSmall.toLong()
        }
        
        // Test with array that's too large
        val tooLarge = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertThrows<IllegalArgumentException> {
            tooLarge.toLong()
        }
    }

    @Test
    fun `test roundtrip conversion`() {
        // Test roundtrip conversion with random values
        repeat(100) {
            val originalLong = Random.nextLong()
            val bytes = originalLong.toByteArray()
            val recoveredLong = bytes.toLong()
            
            assertEquals(originalLong, recoveredLong)
            assertEquals(8, bytes.size)
        }
        
        // Test explicit values
        val testValues = listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 42L, -42L, 
                               1000000000000L, -1000000000000L)
        for (value in testValues) {
            val bytes = value.toByteArray()
            val recovered = bytes.toLong()
            assertEquals(value, recovered)
        }
    }

    @Test
    fun `test values that exceed Int range`() {
        // Test values that are outside the range of Int
        val largePositive = Int.MAX_VALUE.toLong() + 1000L
        val largeNegative = Int.MIN_VALUE.toLong() - 1000L
        
        // Convert to byte arrays
        val largePositiveBytes = largePositive.toByteArray()
        val largeNegativeBytes = largeNegative.toByteArray()
        
        // Convert back to long
        val recoveredLargePositive = largePositiveBytes.toLong()
        val recoveredLargeNegative = largeNegativeBytes.toLong()
        
        // Assert values are preserved
        assertEquals(largePositive, recoveredLargePositive)
        assertEquals(largeNegative, recoveredLargeNegative)
    }
}
