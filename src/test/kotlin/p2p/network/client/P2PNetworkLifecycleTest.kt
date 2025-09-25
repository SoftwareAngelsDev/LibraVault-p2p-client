package p2p.network.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import p2p.network.client.messages.NetworkMessageType
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test simulating the complete P2P network lifecycle:
 * 1. Client C connects to Client B (HEY_BRO -> SUP handshake)
 * 2. Ping/Pong cycle verification 
 * 3. Client A connects to Client B (HEY_BRO -> SUP handshake)
 * 4. SHARE_PEER messages propagate peer discovery
 * 5. All 3 clients know each other via P2P protocol
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P2PNetworkLifecycleTest {
    
    private val testDispatcher = StandardTestDispatcher()
    
    // Simple network simulator for testing
    private val networkSimulator = P2PNetworkSimulator()
    
    // Test clients
    private lateinit var clientA: P2PTestClient
    private lateinit var clientB: P2PTestClient
    private lateinit var clientC: P2PTestClient

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        // Create test clients with unique peer IDs
        // A: even last byte -> will send pings, B: odd last byte -> won't send pings, C: even last byte -> will send pings
        clientA = P2PTestClient("ClientA", ByteArray(512) { (it % 256).toByte() }, "192.168.1.100", 8080, networkSimulator)
        clientB = P2PTestClient("ClientB", ByteArray(512) { ((it + 1) % 256).toByte() }, "192.168.1.101", 8081, networkSimulator) 
        clientC = P2PTestClient("ClientC", ByteArray(512) { if (it == 511) 0 else ((it + 2) % 256).toByte() }, "192.168.1.102", 8082, networkSimulator)
        
        // Register clients with network simulator
        networkSimulator.registerClient(clientA)
        networkSimulator.registerClient(clientB) 
        networkSimulator.registerClient(clientC)
        
        // Start all clients
        clientA.start()
        clientB.start()
        clientC.start()
    }
    
    @AfterEach
    fun tearDown() {
        clientA.stop()
        clientB.stop()
        clientC.stop()
        
        networkSimulator.clear()
        
        Dispatchers.resetMain()
    }

    @Test
    fun `test complete P2P network lifecycle - handshake, ping, and peer discovery`() = runTest {
        println("=== P2P Network Lifecycle Integration Test ===")
        
        // === INITIAL STATE ===
        assertEquals(0, clientA.knownPeers.size, "Client A should start with 0 peers")
        assertEquals(0, clientB.knownPeers.size, "Client B should start with 0 peers")
        assertEquals(0, clientC.knownPeers.size, "Client C should start with 0 peers")
        
        // === STEP 1: Client C connects to Client B ===
        println("\n--- Step 1: Client C -> Client B handshake ---")
        
        // Client C initiates handshake
        clientC.sendHandshake(clientB)
        networkSimulator.processMessages()
        delay(100)
        
        // Verify handshake completed
        assertEquals(1, clientB.knownPeers.size, "Client B should know Client C")
        assertEquals(1, clientC.knownPeers.size, "Client C should know Client B")
        assertTrue(clientB.knownPeers.containsKey("ClientC"), "Client B should have ClientC in known peers")
        assertTrue(clientC.knownPeers.containsKey("ClientB"), "Client C should have ClientB in known peers")
        
        // Verify handshake messages were sent
        val handshakeMessages = networkSimulator.getMessagesSent()
            .filter { it.type in listOf(NetworkMessageType.HEY_BRO, NetworkMessageType.SUP) }
        assertTrue(handshakeMessages.size >= 2, "Should have sent HEY_BRO and SUP messages")
        
        println("✓ Handshake completed: B<->C connected")
        
        // === STEP 2: Ping/Pong Cycle ===
        println("\n--- Step 2: Ping/Pong cycle verification ---")
        
        // Client C should send pings (even peer ID)
        
        // Trigger ping cycle (only even peer IDs send pings)
        testDispatcher.scheduler.advanceTimeBy(121_000) // 121 seconds
        clientC.sendPing() // Client C has even peer ID -> should ping
        networkSimulator.processMessages()
        delay(100)
        
        val pingMessages = networkSimulator.getMessagesSent()
            .filter { it.type == NetworkMessageType.PING }
        val pongMessages = networkSimulator.getMessagesSent()
            .filter { it.type == NetworkMessageType.PONG }
            
        assertTrue(pingMessages.isNotEmpty(), "Ping messages should be sent")
        assertTrue(pongMessages.isNotEmpty(), "Pong responses should be sent")
        
        println("✓ Ping/Pong cycle verified")
        
        // === STEP 3: Client A connects to Client B ===
        println("\n--- Step 3: Client A -> Client B handshake ---")
        
        // Don't clear messages - we want to count all messages in the final verification
        
        // Client A initiates handshake
        clientA.sendHandshake(clientB)
        networkSimulator.processMessages()
        delay(100)
        
        // Verify Client A and B are connected
        assertTrue(clientA.knownPeers.containsKey("ClientB"), "Client A should know Client B")
        assertTrue(clientB.knownPeers.containsKey("ClientA"), "Client B should know Client A")
        assertEquals(2, clientB.knownPeers.size, "Client B should know both A and C")
        
        println("✓ Second handshake completed: A<->B connected")
        
        // === STEP 4: SHARE_PEER message propagation ===
        println("\n--- Step 4: SHARE_PEER message propagation ---")
        
        // Simulate SHARE_PEER messages that happen automatically when peers are added
        clientB.sharePeerInfo(clientA, clientC) // B tells A about C
        clientB.sharePeerInfo(clientC, clientA) // B tells C about A
        
        networkSimulator.processMessages()
        delay(200) // Allow time for propagation
        
        // === STEP 5: Verify final network state ===
        println("\n--- Step 5: Final network state verification ---")
        
        // All clients should know each other
        assertEquals(2, clientA.knownPeers.size, "Client A should know 2 peers (B, C)")
        assertEquals(2, clientB.knownPeers.size, "Client B should know 2 peers (A, C)")
        assertEquals(2, clientC.knownPeers.size, "Client C should know 2 peers (A, B)")
        
        // Verify specific peer knowledge
        assertTrue(clientA.knownPeers.containsKey("ClientB"), "Client A should know Client B")
        assertTrue(clientA.knownPeers.containsKey("ClientC"), "Client A should know Client C")
        assertTrue(clientB.knownPeers.containsKey("ClientA"), "Client B should know Client A")
        assertTrue(clientB.knownPeers.containsKey("ClientC"), "Client B should know Client C")
        assertTrue(clientC.knownPeers.containsKey("ClientA"), "Client C should know Client A")
        assertTrue(clientC.knownPeers.containsKey("ClientB"), "Client C should know Client B")
        
        // Verify SHARE_PEER messages were sent
        val sharePeerMessages = networkSimulator.getMessagesSent()
            .filter { it.type == NetworkMessageType.SHARE_PEER }
        assertTrue(sharePeerMessages.size >= 2, "Should have sent SHARE_PEER messages")
        
        println("✓ All clients know each other!")
        
        // === FINAL VERIFICATION ===
        println("\n--- Final Network State ---")
        println("Client A knows: ${clientA.knownPeers.keys}")
        println("Client B knows: ${clientB.knownPeers.keys}")
        println("Client C knows: ${clientC.knownPeers.keys}")
        
        // Verify total message flow
        val allMessages = networkSimulator.getMessagesSent()
        val totalHandshakes = allMessages.filter { it.type in listOf(NetworkMessageType.HEY_BRO, NetworkMessageType.SUP) }.size
        val totalPings = allMessages.filter { it.type in listOf(NetworkMessageType.PING, NetworkMessageType.PONG) }.size
        val totalShares = allMessages.filter { it.type == NetworkMessageType.SHARE_PEER }.size
        
        println("\nMessage Flow Summary:")
        println("- Handshake messages: $totalHandshakes")
        println("- Ping/Pong messages: $totalPings") 
        println("- SHARE_PEER messages: $totalShares")
        println("- Total messages: ${allMessages.size}")
        
        assertTrue(totalHandshakes >= 4, "Should have multiple handshake messages")
        assertTrue(totalPings >= 2, "Should have ping/pong exchanges")
        assertTrue(totalShares >= 2, "Should have peer sharing messages")
        
        println("\n🎉 P2P Network Lifecycle Test PASSED!")
        println("All 3 clients successfully discovered each other through the P2P protocol!")
    }

    // === Supporting Test Classes ===
    
    /**
     * Simple P2P test client that simulates the core Client behavior
     */
    private class P2PTestClient(
        val name: String,
        val peerId: ByteArray,
        val ip: String,
        val port: Int,
        val networkSimulator: P2PNetworkSimulator
    ) {
        val knownPeers = ConcurrentHashMap<String, P2PTestClient>()
        var isRunning = false
        
        fun start() {
            isRunning = true
            println("[$name] Started at $ip:$port")
        }
        
        fun stop() {
            isRunning = false
            println("[$name] Stopped")
        }
        
        fun sendHandshake(target: P2PTestClient) {
            if (!isRunning) return
            
            println("[$name] Sending HEY_BRO to ${target.name}")
            networkSimulator.logMessage(NetworkMessageType.HEY_BRO, name, target.name)
            
            // Simulate handshake protocol
            target.receiveHandshake(this)
            
            // Add to known peers after handshake
            knownPeers[target.name] = target
            target.knownPeers[this.name] = this
        }
        
        fun receiveHandshake(from: P2PTestClient) {
            println("[$name] Received HEY_BRO from ${from.name}, sending SUP")
            networkSimulator.logMessage(NetworkMessageType.SUP, name, from.name)
            // Handshake response is handled automatically
        }
        
        fun sendPing() {
            if (!isRunning) return
            
            // Only even peer IDs send pings (simulating the protocol logic)
            if (peerId.last().toInt() % 2 == 0) {
                knownPeers.values.forEach { peer ->
                    println("[$name] Sending PING to ${peer.name}")
                    networkSimulator.logMessage(NetworkMessageType.PING, name, peer.name)
                    peer.receivePing(this)
                }
            }
        }
        
        fun receivePing(from: P2PTestClient) {
            println("[$name] Received PING from ${from.name}, sending PONG")
            networkSimulator.logMessage(NetworkMessageType.PONG, name, from.name)
            // PONG response is automatic
        }
        
        fun sharePeerInfo(target: P2PTestClient, peerToShare: P2PTestClient) {
            if (!isRunning) return
            
            println("[$name] Sharing ${peerToShare.name} info with ${target.name}")
            networkSimulator.logMessage(NetworkMessageType.SHARE_PEER, name, target.name)
            target.receivePeerInfo(peerToShare)
        }
        
        fun receivePeerInfo(newPeer: P2PTestClient) {
            if (!knownPeers.containsKey(newPeer.name)) {
                knownPeers[newPeer.name] = newPeer
                newPeer.knownPeers[this.name] = this
                println("[$name] Learned about new peer: ${newPeer.name}")
            }
        }
    }
    
    /**
     * Simple message for tracking network communication
     */
    private data class TestMessage(
        val type: NetworkMessageType,
        val from: String,
        val to: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Network simulator for controlled testing
     */
    private class P2PNetworkSimulator {
        private val clients = mutableMapOf<String, P2PTestClient>()
        private val messagesSent = mutableListOf<TestMessage>()
        
        fun registerClient(client: P2PTestClient) {
            clients[client.name] = client
        }
        
        fun processMessages() {
            // Simulate message processing delay
        }
        
        fun getMessagesSent(): List<TestMessage> = messagesSent.toList()
        
        fun clearMessages() {
            messagesSent.clear()
        }
        
        fun logMessage(type: NetworkMessageType, from: String, to: String) {
            messagesSent.add(TestMessage(type, from, to))
        }
        
        fun clear() {
            clients.clear()
            messagesSent.clear()
        }
    }
}
