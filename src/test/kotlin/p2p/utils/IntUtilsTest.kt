package p2p.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import kotlin.random.Random

class IntUtilsTest {

    @Test
    fun `test Int toByteArray`() {
        // Test zero
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), 0.toByteArray())
        
        // Test positive value
        val expected1 = ByteBuffer.allocate(4).putInt(42).array()
        assertArrayEquals(expected1, 42.toByteArray())
        
        // Test negative value
        val expected2 = ByteBuffer.allocate(4).putInt(-1).array()
        assertArrayEquals(expected2, (-1).toByteArray())
        
        // Test maximum int value
        val expected3 = ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()
        assertArrayEquals(expected3, Int.MAX_VALUE.toByteArray())
        
        // Test minimum int value
        val expected4 = ByteBuffer.allocate(4).putInt(Int.MIN_VALUE).array()
        assertArrayEquals(expected4, Int.MIN_VALUE.toByteArray())
    }

    @Test
    fun `test ByteArray toInt`() {
        // Test zero
        assertEquals(0, byteArrayOf(0, 0, 0, 0).toInt())
        
        // Test positive value
        val bytes1 = ByteBuffer.allocate(4).putInt(42).array()
        assertEquals(42, bytes1.toInt())
        
        // Test negative value
        val bytes2 = ByteBuffer.allocate(4).putInt(-1).array()
        assertEquals(-1, bytes2.toInt())
        
        // Test maximum int value
        val bytes3 = ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()
        assertEquals(Int.MAX_VALUE, bytes3.toInt())
        
        // Test minimum int value
        val bytes4 = ByteBuffer.allocate(4).putInt(Int.MIN_VALUE).array()
        assertEquals(Int.MIN_VALUE, bytes4.toInt())
    }

    @Test
    fun `test ByteArray toInt with invalid array size`() {
        // Test with array that's too small
        val tooSmall = byteArrayOf(1, 2, 3)
        assertThrows<IllegalArgumentException> {
            tooSmall.toInt()
        }
        
        // Test with array that's too large
        val tooLarge = byteArrayOf(1, 2, 3, 4, 5)
        assertThrows<IllegalArgumentException> {
            tooLarge.toInt()
        }
    }

    @Test
    fun `test roundtrip conversion`() {
        // Test roundtrip conversion with random values
        repeat(100) {
            val originalInt = Random.nextInt()
            val bytes = originalInt.toByteArray()
            val recoveredInt = bytes.toInt()
            
            assertEquals(originalInt, recoveredInt)
            assertEquals(4, bytes.size)
        }
        
        // Test explicit values
        val testValues = listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 42, -42)
        for (value in testValues) {
            val bytes = value.toByteArray()
            val recovered = bytes.toInt()
            assertEquals(value, recovered)
        }
    }
}
