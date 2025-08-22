package p2p.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.random.Random

class ByteUtilsTest {

    @Test
    fun `test convertKBToBytes`() {
        // Test conversion with zero
        assertEquals(0L, convertKBToBytes(0))
        
        // Test conversion with a positive value
        assertEquals(1024L, convertKBToBytes(1))
        
        // Test conversion with a larger value
        assertEquals(102400L, convertKBToBytes(100))
        
        // Test conversion with maximum possible value that doesn't overflow
        val maxKB = Long.MAX_VALUE / 1024
        assertEquals(maxKB * 1024, convertKBToBytes(maxKB))
    }

    @Test
    fun `test convertMBToBytes`() {
        // Test conversion with zero
        assertEquals(0L, convertMBToBytes(0))
        
        // Test conversion with a positive value
        assertEquals(1048576L, convertMBToBytes(1)) // 1MB = 1,048,576 bytes
        
        // Test conversion with a larger value
        assertEquals(104857600L, convertMBToBytes(100))
        
        // Test conversion with maximum possible value that doesn't overflow
        val maxMB = Long.MAX_VALUE / (1024 * 1024)
        assertEquals(maxMB * 1024 * 1024, convertMBToBytes(maxMB))
    }

    @Test
    fun `test convertGBToBytes`() {
        // Test conversion with zero
        assertEquals(0L, convertGBToBytes(0))
        
        // Test conversion with a positive value
        assertEquals(1073741824L, convertGBToBytes(1)) // 1GB = 1,073,741,824 bytes
        
        // Test conversion with a larger value
        assertEquals(107374182400L, convertGBToBytes(100))
        
        // Test conversion with maximum possible value that doesn't overflow
        val maxGB = Long.MAX_VALUE / (1024L * 1024L * 1024L)
        assertEquals(maxGB * 1024L * 1024L * 1024L, convertGBToBytes(maxGB))
    }

    @Test
    fun `test mergeByteArrays with empty arrays`() {
        // Test with no arrays
        assertArrayEquals(ByteArray(0), mergeByteArrays())
        
        // Test with single empty array
        assertArrayEquals(ByteArray(0), mergeByteArrays(ByteArray(0)))
        
        // Test with multiple empty arrays
        assertArrayEquals(ByteArray(0), mergeByteArrays(ByteArray(0), ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `test mergeByteArrays with non-empty arrays`() {
        // Test with a single array
        val singleArray = byteArrayOf(1, 2, 3)
        assertArrayEquals(singleArray, mergeByteArrays(singleArray))
        
        // Test with multiple arrays
        val array1 = byteArrayOf(1, 2, 3)
        val array2 = byteArrayOf(4, 5)
        val array3 = byteArrayOf(6, 7, 8, 9)
        
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertArrayEquals(expected, mergeByteArrays(array1, array2, array3))
    }

    @Test
    fun `test mergeByteArrays with mixed empty and non-empty arrays`() {
        // Test with mixed empty and non-empty arrays
        val emptyArray = ByteArray(0)
        val array1 = byteArrayOf(1, 2, 3)
        val array2 = byteArrayOf(4, 5)
        
        // Empty + Non-empty
        assertArrayEquals(array1, mergeByteArrays(emptyArray, array1))
        
        // Non-empty + Empty
        assertArrayEquals(array1, mergeByteArrays(array1, emptyArray))
        
        // Empty + Non-empty + Non-empty
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), mergeByteArrays(emptyArray, array1, array2))
        
        // Non-empty + Empty + Non-empty
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), mergeByteArrays(array1, emptyArray, array2))
    }

    @Test
    fun `test mergeByteArrays with large arrays`() {
        // Create two large random byte arrays
        val size1 = 10000
        val size2 = 5000
        val array1 = Random.nextBytes(size1)
        val array2 = Random.nextBytes(size2)
        
        // Merge arrays
        val merged = mergeByteArrays(array1, array2)
        
        // Verify size
        assertEquals(size1 + size2, merged.size)
        
        // Verify content
        for (i in 0 until size1) {
            assertEquals(array1[i], merged[i])
        }
        
        for (i in 0 until size2) {
            assertEquals(array2[i], merged[size1 + i])
        }
    }
}
