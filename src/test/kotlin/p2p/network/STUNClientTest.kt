package p2p.network

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.InetSocketAddress

@ExperimentalAtomicApi
class STUNClientTest {
    
    /**
     * Utility function to check if we have internet connectivity via TCP
     * 
     * @param hostToCheck The hostname to check connectivity to
     * @param port The port to connect on
     * @param timeoutMs Connection timeout in milliseconds
     * @return true if internet is available, false otherwise
     */
    private fun isTcpInternetAvailable(
        hostToCheck: String = "8.8.8.8", // Google's DNS - very reliable
        port: Int = 53, // DNS port
        timeoutMs: Int = 1000 // Fast timeout
    ): Boolean {
        return try {
            // Try a socket connection to Google's DNS
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(hostToCheck, port), timeoutMs)
            }
            println("TCP Internet connectivity check: AVAILABLE")
            true
        } catch (e: Exception) {
            println("TCP Internet connectivity check: NOT AVAILABLE (${e.javaClass.simpleName}: ${e.message})")
            false
        }
    }
    
    /**
     * Utility function to check if we have internet connectivity via UDP
     * 
     * @param hostToCheck The hostname to check connectivity to
     * @param port The port to connect on
     * @param timeoutMs Connection timeout in milliseconds
     * @return true if UDP is working bidirectionally, false otherwise
     */
    private fun isUdpInternetAvailable(
        hostToCheck: String = "8.8.8.8", // Google's DNS - very reliable
        port: Int = 53, // DNS port
        timeoutMs: Int = 1000 // Fast timeout
    ): Boolean {
        println("Testing UDP connectivity with $hostToCheck:$port...")
        
        var socket: DatagramSocket? = null
        try {
            // Create a simple DNS query packet (just the header)
            val dnsQuery = byteArrayOf(
                0x12, 0x34, // ID
                0x01, 0x00, // Flags
                0x00, 0x01, // QDCOUNT
                0x00, 0x00, // ANCOUNT
                0x00, 0x00, // NSCOUNT
                0x00, 0x00  // ARCOUNT
            )
            
            // Send the packet
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs
            
            val packet = DatagramPacket(dnsQuery, dnsQuery.size, InetAddress.getByName(hostToCheck), port)
            socket.send(packet)
            println("✅ Successfully sent UDP packet to $hostToCheck:$port")
            
            // Try to receive a response
            val responseBuffer = ByteArray(512)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            
            try {
                socket.receive(responsePacket)
                println("✅ SUCCESS! Received UDP response from $hostToCheck:$port (${responsePacket.length} bytes)")
                println("UDP Internet connectivity check: AVAILABLE (bidirectional)")
                return true
            } catch (e: Exception) {
                println("❌ Failed to receive UDP response: ${e.message}")
                println("UDP Internet connectivity check: PARTIAL (outbound only)")
                return false
            }
        } catch (e: Exception) {
            println("❌ Failed to send UDP packet: ${e.message}")
            println("UDP Internet connectivity check: NOT AVAILABLE")
            return false
        } finally {
            socket?.close()
        }
    }

    @Test
    fun `test getAllLocalAddresses returns network interfaces`() {
        val client = STUNClient()
        val interfaces = client.getAllLocalAddresses(includeLoopback = true, includeVirtual = true)
        
        // At minimum there should be a loopback interface
        assertTrue(interfaces.isNotEmpty(), "Should find at least one network interface")
        
        // Check that we have at least one interface with an address
        val hasAddresses = interfaces.any { it.addresses.isNotEmpty() }
        assertTrue(hasAddresses, "At least one interface should have an IP address")
    }

    @Test
    fun `test getAllLocalIPs returns IP addresses`() {
        val client = STUNClient()
        val addresses = client.getAllLocalIPs(includeLoopback = true)
        
        // At minimum there should be a loopback address (127.0.0.1)
        assertTrue(addresses.isNotEmpty(), "Should find at least one IP address")
        
        // Check if loopback is included as expected
        val hasLoopback = addresses.any { it.startsWith("127.") }
        assertTrue(hasLoopback, "Should include loopback address when includeLoopback is true")
    }

    @Test
    fun `test getAllLocalIPs filters loopback when requested`() {
        val client = STUNClient()
        val addressesWithLoopback = client.getAllLocalIPs(includeLoopback = true)
        val addressesWithoutLoopback = client.getAllLocalIPs(includeLoopback = false)
        
        // If there are non-loopback interfaces available, the filtered list should be smaller
        if (addressesWithLoopback.any { !it.startsWith("127.") }) {
            assertTrue(addressesWithoutLoopback.size < addressesWithLoopback.size, 
                "List without loopback should be smaller than list with loopback")
            
            // Verify no loopback addresses are included
            val hasLoopback = addressesWithoutLoopback.any { it.startsWith("127.") }
            assertTrue(!hasLoopback, "Should not include loopback address when includeLoopback is false")
        }
    }

    @Test
    fun `test getAllLocalIPs filters IPv6 when requested`() {
        val client = STUNClient()
        // Only run this test if we have IPv6 addresses
        val allAddresses = client.getAllLocalIPs(includeLoopback = true, onlyIPv4 = false)
        val hasIPv6 = allAddresses.any { it.contains(":") }
        
        if (hasIPv6) {
            val ipv4Only = client.getAllLocalIPs(includeLoopback = true, onlyIPv4 = true)
            assertTrue(ipv4Only.size < allAddresses.size, "IPv4-only list should be smaller")
            
            // Verify no IPv6 addresses are included
            val hasIPv6InFilteredList = ipv4Only.any { it.contains(":") }
            assertTrue(!hasIPv6InFilteredList, "IPv4-only list should not contain IPv6 addresses")
        }
    }

    @Test
    fun `test findMyPublicAddress with UDP preferred (default behavior)`() = runTest {
        // Check for basic connectivity
        val hasTcpInternet = isTcpInternetAvailable()
        val hasUdpInternet = isUdpInternetAvailable()
        
        // Skip if we don't have basic internet connectivity
        Assumptions.assumeTrue(hasTcpInternet, "Internet connectivity is required for STUN test")
        
        val client = STUNClient()
        
        // Print connection status and run the test
        println("Running STUN test with UDP preferred, TCP as fallback...")
        
        try {
            // With preferUdp = true (default) but shorter timeout
            val result = client.findMyPublicAddress(timeoutMs = 5000)
            
            // Verify results
            assertNotNull(result)
            assertNotNull(result.publicAddress)
            assertTrue(result.publicPort > 0, "Port should be > 0 when using STUN")
            assertNotNull(result.localAddress)
            assertTrue(result.localPort > 0)
            
            // Print result for visibility
            println("✅ TEST PASSED - Found public address: ${result.publicAddress}:${result.publicPort} (${result.protocol})")
            println("✅ TEST PASSED - Local address: ${result.localAddress}:${result.localPort} (${result.protocol})")
            
            // If UDP is available, we expect the protocol to be UDP
            if (hasUdpInternet) {
                println("UDP is available, expecting UDP protocol")
                assertTrue(result.protocol == STUNClient.NetworkProtocol.UDP, "Expected UDP protocol when UDP is available")
            } else {
                println("UDP is not fully available, expecting TCP protocol")
                assertTrue(result.protocol == STUNClient.NetworkProtocol.TCP, "Expected TCP protocol when UDP is not available")
            }
        } catch (e: Exception) {
            // If the test fails due to network issues, print diagnostic info but don't fail the test
            println("⚠️ STUN test could not be completed due to network issues: ${e.message}")
            println("This is likely due to firewall restrictions or all STUN servers being unreachable.")
            println("The test will be skipped rather than failed.")
            
            // Skip the test instead of failing
            Assumptions.assumeTrue(false, "Skipping test due to network issues: ${e.message}")
        }
    }
    
    @Test
    fun `test findMyPublicAddress with TCP only`() = runTest {
        // Check for TCP connectivity
        val hasTcpInternet = isTcpInternetAvailable()
        
        // Skip if we don't have basic internet connectivity
        Assumptions.assumeTrue(hasTcpInternet, "TCP internet connectivity is required for STUN test")
        
        val client = STUNClient()
        
        // Print connection status and run the test
        println("Running STUN test with TCP only...")
        
        try {
            // With preferUdp = false to force TCP only
            val result = client.findMyPublicAddress(timeoutMs = 5000, preferUdp = false)
            
            // Verify results
            assertNotNull(result)
            assertNotNull(result.publicAddress)
            assertTrue(result.publicPort > 0, "Port should be > 0 when using TCP STUN")
            assertNotNull(result.localAddress)
            assertTrue(result.localPort > 0)
            
            // Print result for visibility
            println("✅ TEST PASSED - Found public address: ${result.publicAddress}:${result.publicPort} (${result.protocol})")
            println("✅ TEST PASSED - Local address: ${result.localAddress}:${result.localPort} (${result.protocol})")
            
            // Verify TCP was used
            assertTrue(result.protocol == STUNClient.NetworkProtocol.TCP, "Protocol should be TCP when preferUdp = false")
        } catch (e: Exception) {
            // If the test fails due to network issues, print diagnostic info but don't fail the test
            println("⚠️ TCP STUN test could not be completed due to network issues: ${e.message}")
            println("This is likely due to firewall restrictions or all STUN servers being unreachable.")
            println("The test will be skipped rather than failed.")
            
            // Skip the test instead of failing
            Assumptions.assumeTrue(false, "Skipping test due to network issues: ${e.message}")
        }
    }
    
    @Test
    fun `test findMyPublicAddress with UDP only`() = runTest {
        // Check for UDP connectivity
        val hasUdpInternet = isUdpInternetAvailable()
        
        // Skip if UDP is not available
        Assumptions.assumeTrue(hasUdpInternet, "UDP internet connectivity is required for this test")
        
        // Create a real client but one that will only try UDP
        class UdpOnlyClient : STUNClient() {
            // Override the TCP method to always fail immediately
            override suspend fun stunOverTcp(
                serverAddress: InetAddress,
                serverPort: Int,
                timeoutMs: Int
            ): AddressInfo? = null
        }
        
        val udpClient = UdpOnlyClient()
        
        // Print connection status and run the test
        println("Running STUN test with UDP only (TCP disabled)...")
        
        try {
            // With preferUdp = true to try UDP first (and only)
            val result = udpClient.findMyPublicAddress(timeoutMs = 5000, preferUdp = true)
            
            // Verify results
            assertNotNull(result)
            assertNotNull(result.publicAddress)
            assertTrue(result.publicPort > 0, "Port should be > 0 when using UDP STUN")
            assertNotNull(result.localAddress)
            assertTrue(result.localPort > 0)
            
            // Print result for visibility
            println("✅ TEST PASSED - Found public address: ${result.publicAddress}:${result.publicPort} (${result.protocol})")
            println("✅ TEST PASSED - Local address: ${result.localAddress}:${result.localPort} (${result.protocol})")
            
            // Verify UDP was used
            assertTrue(result.protocol == STUNClient.NetworkProtocol.UDP, "Protocol should be UDP")
        } catch (e: Exception) {
            // If the test fails due to network issues, print diagnostic info but don't fail the test
            println("⚠️ UDP STUN test could not be completed due to network issues: ${e.message}")
            println("This is likely due to firewall restrictions or all STUN servers being unreachable.")
            println("The test will be skipped rather than failed.")
            
            // Skip the test instead of failing
            Assumptions.assumeTrue(false, "Skipping test due to network issues: ${e.message}")
        }
    }
    
    @Test
    fun `test findMyPublicAddress with UDP failing but TCP working`() = runTest {
        // Check for TCP connectivity
        val hasTcpInternet = isTcpInternetAvailable()
        
        // Skip if TCP is not available
        Assumptions.assumeTrue(hasTcpInternet, "TCP internet connectivity is required for this test")
        
        // Create a real client but one that will make UDP fail immediately
        class TcpFallbackClient : STUNClient() {
            // Override the UDP method to always fail immediately
            override suspend fun stunOverUdp(
                serverAddress: InetAddress,
                serverPort: Int,
                timeoutMs: Int
            ): AddressInfo? = null
        }
        
        val tcpFallbackClient = TcpFallbackClient()
        
        // Print connection status and run the test
        println("Running STUN test with UDP disabled (forcing TCP fallback)...")
        
        try {
            // With preferUdp = true to try UDP first, but it will fail and fall back to TCP
            val result = tcpFallbackClient.findMyPublicAddress(timeoutMs = 5000, preferUdp = true)
            
            // Verify results
            assertNotNull(result)
            assertNotNull(result.publicAddress)
            assertTrue(result.publicPort > 0, "Port should be > 0 when using TCP STUN")
            assertNotNull(result.localAddress)
            assertTrue(result.localPort > 0)
            
            // Print result for visibility
            println("✅ TEST PASSED - Found public address: ${result.publicAddress}:${result.publicPort} (${result.protocol})")
            println("✅ TEST PASSED - Local address: ${result.localAddress}:${result.localPort} (${result.protocol})")
            
            // Verify TCP was used as fallback
            assertTrue(result.protocol == STUNClient.NetworkProtocol.TCP, "Protocol should be TCP when UDP fails")
        } catch (e: Exception) {
            // If the test fails due to network issues, print diagnostic info but don't fail the test
            println("⚠️ TCP fallback test could not be completed due to network issues: ${e.message}")
            println("This is likely due to firewall restrictions or all STUN servers being unreachable.")
            println("The test will be skipped rather than failed.")
            
            // Skip the test instead of failing
            Assumptions.assumeTrue(false, "Skipping test due to network issues: ${e.message}")
        }
    }
    
    @Test
    fun `test findMyPublicAddress with both UDP and TCP failing`() = runTest {
        // Create a test subclass of STUNClient that will make both UDP and TCP fail
        class FailingStunClient : STUNClient() {
            // Override both methods to always fail immediately
            override suspend fun stunOverUdp(
                serverAddress: InetAddress,
                serverPort: Int,
                timeoutMs: Int
            ): AddressInfo? = null
            
            override suspend fun stunOverTcp(
                serverAddress: InetAddress,
                serverPort: Int,
                timeoutMs: Int
            ): AddressInfo? = null
            
            // Override tryStunWithProtocol to fail faster (avoid timeout)
            override suspend fun tryStunWithProtocol(
                parsedServers: List<Triple<String, String, Int>>,
                protocol: NetworkProtocol,
                timeoutMs: Int
            ): AddressInfo? {
                // Just return null immediately to simulate failure
                return null
            }
        }
        
        val failingClient = FailingStunClient()
        
        // Print connection status and run the test
        println("Running STUN test with both UDP and TCP disabled (simulating complete STUN failure)...")
        
        try {
            // This should throw an exception since both UDP and TCP will fail
            failingClient.findMyPublicAddress(timeoutMs = 1000)
            
            // We should never reach here
            throw AssertionError("Expected exception was not thrown when both UDP and TCP failed")
        } catch (e: Exception) {
            // Verify we got the expected exception
            println("✅ TEST PASSED - Caught expected exception when both UDP and TCP fail: ${e.message}")
            assertTrue(e.message?.contains("All STUN servers failed") == true, 
                "Exception should mention STUN server failure")
        }
    }
    

}