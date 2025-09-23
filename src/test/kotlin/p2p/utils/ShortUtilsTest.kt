package p2p.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import kotlin.random.Random

class ShortUtilsTest {

    @Test
    fun `test Short toByteArray`() {
        // Test zero
        assertArrayEquals(byteArrayOf(0, 0), 0.toShort().toByteArray())
        
        // Test positive value
        val expected1 = ByteBuffer.allocate(2).putShort(42).array()
        assertArrayEquals(expected1, 42.toShort().toByteArray())
        
        // Test negative value
        val expected2 = ByteBuffer.allocate(2).putShort(-1).array()
        assertArrayEquals(expected2, (-1).toShort().toByteArray())
        
        // Test maximum short value
        val expected3 = ByteBuffer.allocate(2).putShort(Short.MAX_VALUE).array()
        assertArrayEquals(expected3, Short.MAX_VALUE.toByteArray())
        
        // Test minimum short value
        val expected4 = ByteBuffer.allocate(2).putShort(Short.MIN_VALUE).array()
        assertArrayEquals(expected4, Short.MIN_VALUE.toByteArray())
    }

    @Test
    fun `test ByteArray toShort`() {
        // Test zero
        assertEquals(0.toShort(), byteArrayOf(0, 0).toShort())
        
        // Test positive value
        val bytes1 = ByteBuffer.allocate(2).putShort(42).array()
        assertEquals(42.toShort(), bytes1.toShort())
        
        // Test negative value
        val bytes2 = ByteBuffer.allocate(2).putShort(-1).array()
        assertEquals((-1).toShort(), bytes2.toShort())
        
        // Test maximum short value
        val bytes3 = ByteBuffer.allocate(2).putShort(Short.MAX_VALUE).array()
        assertEquals(Short.MAX_VALUE, bytes3.toShort())
        
        // Test minimum short value
        val bytes4 = ByteBuffer.allocate(2).putShort(Short.MIN_VALUE).array()
        assertEquals(Short.MIN_VALUE, bytes4.toShort())
    }

    @Test
    fun `test ByteArray toShort with invalid array size`() {
        // Test with array that's too small
        val tooSmall = byteArrayOf(1)
        assertThrows<IllegalArgumentException> {
            tooSmall.toShort()
        }
        
        // Test with array that's too large
        val tooLarge = byteArrayOf(1, 2, 3)
        assertThrows<IllegalArgumentException> {
            tooLarge.toShort()
        }
        
        // Test with empty array
        val empty = byteArrayOf()
        assertThrows<IllegalArgumentException> {
            empty.toShort()
        }
    }

    @Test
    fun `test roundtrip conversion`() {
        // Test roundtrip conversion with random values
        repeat(100) {
            val originalShort = Random.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt() + 1).toShort()
            val bytes = originalShort.toByteArray()
            val recoveredShort = bytes.toShort()
            
            assertEquals(originalShort, recoveredShort)
            assertEquals(2, bytes.size)
        }
        
        // Test explicit values
        val testValues = listOf<Short>(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 42, -42, 255, -255, 1000, -1000)
        for (value in testValues) {
            val bytes = value.toByteArray()
            val recovered = bytes.toShort()
            assertEquals(value, recovered)
        }
    }

    @Test
    fun `test specific port values`() {
        // Test common port values since Short is often used for network ports
        val commonPorts = listOf(80.toShort(), 443.toShort(), 8080.toShort(), 3000.toShort(), 9192.toShort(), 65535.toShort())
        
        for (port in commonPorts) {
            val bytes = port.toByteArray()
            val recovered = bytes.toShort()
            assertEquals(port, recovered)
            assertEquals(2, bytes.size)
        }
    }

    @Test
    fun `test byte array size is always 2`() {
        // Test that Short.toByteArray() always produces 2-byte arrays
        val testValues = listOf<Short>(0, 1, -1, 100, -100, Short.MAX_VALUE, Short.MIN_VALUE)
        
        for (value in testValues) {
            val bytes = value.toByteArray()
            assertEquals(2, bytes.size, "Short.toByteArray() should always produce 2-byte arrays")
        }
    }
}
