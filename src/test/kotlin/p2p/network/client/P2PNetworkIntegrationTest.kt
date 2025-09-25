package p2p.network.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import p2p.domain.wtfs.PeerPrivateKey
import p2p.domain.wtfs.PeerPublicKey
import p2p.helpers.ConfigurationManager
import p2p.helpers.RemotePeerReputationManager
import p2p.network.PeerNetworkInfo
import p2p.network.client.messages.*
import p2p.utils.LoggerInterface
import p2p.utils.toByteArray
import p2p.utils.toInt
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class P2PNetworkIntegrationTest {
    
    companion object {
        private const val TEST_VERSION = 1
    }
    
    private val testDispatcher = StandardTestDispatcher()
    
    // Test network infrastructure
    private val networkSimulator = InMemoryNetworkSimulator()
    
    // Test clients
    private lateinit var clientA: Client
    private lateinit var clientB: Client
    private lateinit var clientC: Client
    
    // Store reference to PingPongMessageHandler for manual triggering
    private lateinit var pingPongHandlerC: p2p.network.client.messages.PingPongMessageHandler
    
    // Client configurations
    private lateinit var configA: ConfigurationManager
    private lateinit var configB: ConfigurationManager
    private lateinit var configC: ConfigurationManager
    
    // Test peer identities
    private lateinit var peerIdA: PeerPublicKey
    private lateinit var peerIdB: PeerPublicKey
    private lateinit var peerIdC: PeerPublicKey
    
    // Network info for each peer
    private lateinit var peerInfoA: PeerNetworkInfo
    private lateinit var peerInfoB: PeerNetworkInfo
    private lateinit var peerInfoC: PeerNetworkInfo
    
    // Activity listeners for testing
    private val clientAActivity = TestActivityListener("ClientA")
    private val clientBActivity = TestActivityListener("ClientB")
    private val clientCActivity = TestActivityListener("ClientC")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        // Create unique peer IDs
        peerIdA = PeerPublicKey(ByteArray(512) { (it % 256).toByte() }) // Even last byte - will send pings
        peerIdB = PeerPublicKey(ByteArray(512) { ((it + 1) % 256).toByte() }) // Odd last byte - won't send pings  
        peerIdC = PeerPublicKey(ByteArray(512) { ((it + 2) % 256).toByte() }) // Even last byte - will send pings
        
        // Create network info for each peer
        peerInfoA = PeerNetworkInfo(peerIdA, "192.168.1.100", 8080, TEST_VERSION)
        peerInfoB = PeerNetworkInfo(peerIdB, "192.168.1.101", 8081, TEST_VERSION)
        peerInfoC = PeerNetworkInfo(peerIdC, "192.168.1.102", 8082, TEST_VERSION)
        
        // Create configurations for each client
        configA = createMockConfig(peerIdA, 8080)
        configB = createMockConfig(peerIdB, 8081)
        configC = createMockConfig(peerIdC, 8082)
        
        // Create clients with test infrastructure
        clientA = createTestClient("ClientA", configA, peerInfoA)
        clientB = createTestClient("ClientB", configB, peerInfoB)
        clientC = createTestClient("ClientC", configC, peerInfoC)
        
        // Add activity listeners
        clientA.addActivityListener(clientAActivity)
        clientB.addActivityListener(clientBActivity)
        clientC.addActivityListener(clientCActivity)
        
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
    
    private fun createMockConfig(publicKey: PeerPublicKey, port: Int): ConfigurationManager {
        val config = mock<ConfigurationManager>()
        whenever(config.publicKey).thenReturn(publicKey)
        whenever(config.udpPort).thenReturn(port)
        whenever(config.privateKey).thenReturn(PeerPrivateKey(ByteArray(512)))
        whenever(config.repositoryAbsolutePath).thenReturn("/tmp/test")
        return config
    }
    
    private fun createTestClient(name: String, config: ConfigurationManager, peerInfo: PeerNetworkInfo): Client {
        val logger = TestLogger(name)
        val reputationManager = mock<RemotePeerReputationManager>()
        
        val client = spy(Client(config, logger, reputationManager))
        
        // Replace the transmitter with our test implementation
        val testTransmitter = TestClientTransmitter(config, logger, client, peerInfo, networkSimulator)
        replaceTransmitter(client, testTransmitter)
        
        // Add all message handlers
        client.addMessageHandler(HeyMessageHandler(config, logger))
        client.addMessageHandler(SharePeerMessageHandler(config, logger))
        client.addMessageHandler(PingPongMessageHandler(config, logger))
        client.addMessageHandler(SeeYaBroMessageHandler(config, logger))
        client.addMessageHandler(SupMessageHandler(config, logger)) // Create SUP handler
        
        return client
    }
    
    private fun createTestClientWithPingHandler(
        loggerName: String,
        config: ConfigurationManager, 
        peerInfo: PeerNetworkInfo,
        pingPongHandler: p2p.network.client.messages.PingPongMessageHandler
    ): Client {
        val logger = TestLogger(loggerName)
        val reputationManager = mock<RemotePeerReputationManager>()
        
        val client = spy(Client(config, logger, reputationManager))
        
        // Replace the transmitter with our test implementation
        val testTransmitter = TestClientTransmitter(config, logger, client, peerInfo, networkSimulator)
        replaceTransmitter(client, testTransmitter)
        
        // Add all message handlers (using the provided pingPongHandler for manual control)
        client.addMessageHandler(HeyMessageHandler(config, logger))
        client.addMessageHandler(SharePeerMessageHandler(config, logger))
        client.addMessageHandler(pingPongHandler) // Use the provided handler
        client.addMessageHandler(SeeYaBroMessageHandler(config, logger))
        client.addMessageHandler(SupMessageHandler(config, logger))
        
        return client
    }
    
    private fun replaceTransmitter(client: Client, transmitter: TestClientTransmitter) {
        val transmitterField = Client::class.java.getDeclaredField("transmitter")
        transmitterField.isAccessible = true
        transmitterField.set(client, transmitter)
    }

    @Test
    fun `test full P2P network lifecycle with 3 clients`() = runTest {
        println("=== P2P Network Test Setup ===")
        println("ClientA: ${peerInfoA.publicIp}:${peerInfoA.publicPort} (ID: ${peerIdA.toString().take(10)}...)")
        println("ClientB: ${peerInfoB.publicIp}:${peerInfoB.publicPort} (ID: ${peerIdB.toString().take(10)}...)")
        println("ClientC: ${peerInfoC.publicIp}:${peerInfoC.publicPort} (ID: ${peerIdC.toString().take(10)}...)")
        println()
        
        // Initially, all clients should have no known peers
        assertEquals(0, clientA.getKnownPeers().size)
        assertEquals(0, clientB.getKnownPeers().size)
        assertEquals(0, clientC.getKnownPeers().size)
        
        println("=== Step 1: Client C connects to Client B ===")
        // Client C initiates handshake with Client B
        clientC.transmit(
            NetworkMessageType.HEY_BRO, 
            peerInfoB, 
            VERSION.toByteArray()
        )
        
        // Process all pending messages
        networkSimulator.processAllMessages()
        delay(100) // Allow processing time
        
        // After handshake, both B and C should know each other
        
        // Debug output
        println("DEBUG: ClientB known peers: ${clientB.getKnownPeers().keys.map { it.toString().take(10) }}")
        println("DEBUG: ClientC known peers: ${clientC.getKnownPeers().keys.map { it.toString().take(10) }}")
        println("DEBUG: peerIdB: ${peerIdB.toString().take(10)}")
        println("DEBUG: peerIdC: ${peerIdC.toString().take(10)}")
        
        val clientBKnowsC = clientB.getKnownPeers().containsKey(peerIdC)
        val clientCKnowsB = clientC.getKnownPeers().containsKey(peerIdB)
        
        println("DEBUG: clientBKnowsC = $clientBKnowsC")
        println("DEBUG: clientCKnowsB = $clientCKnowsB")
        
        assertTrue(clientBKnowsC, "Client B should know Client C")
        assertTrue(clientCKnowsB, "Client C should know Client B")
        assertEquals(1, clientB.getKnownPeers().size, "Client B should know 1 peer")
        assertEquals(1, clientC.getKnownPeers().size, "Client C should know 1 peer")
        assertEquals(0, clientA.getKnownPeers().size, "Client A should still know 0 peers")
        
        // Verify activity listeners were notified
        assertTrue(clientBActivity.peersAdded.contains(peerIdC), "Client B should have received peer added event for C")
        assertTrue(clientCActivity.peersAdded.contains(peerIdB), "Client C should have received peer added event for B")
        
        println("=== Step 2: Wait for ping cycle (C should ping B) ===")
        // Client C has even peer ID, so it should send pings
        // Client B has odd peer ID, so it won't send pings
        
        // Manually trigger ping cycle for testing - simulate what tick() does
        // ClientC should send a PING to ClientB (since C has even peer ID)
        clientC.transmit(
            NetworkMessageType.PING,
            peerInfoB,
            ByteArray(0)
        )
        
        networkSimulator.processAllMessages()
        delay(100)
        
        // Verify pings were sent and ponged
        val sentMessages = networkSimulator.getSentMessages()
        val pingsSent = sentMessages.filter { it.type == NetworkMessageType.PING }
        val pongsSent = sentMessages.filter { it.type == NetworkMessageType.PONG }
        
        assertTrue(pingsSent.isNotEmpty(), "Ping messages should have been sent")
        assertTrue(pongsSent.isNotEmpty(), "Pong responses should have been sent")
        
        println("=== Step 3: Client A connects to Client B ===")
        // Client A initiates handshake with Client B  
        clientA.transmit(
            NetworkMessageType.HEY_BRO,
            peerInfoB, 
            VERSION.toByteArray()
        )
        
        // Process handshake messages
        networkSimulator.processAllMessages()
        delay(200) // Allow more time for SHARE_PEER messages
        
        // Now B should know both A and C
        assertEquals(2, clientB.getKnownPeers().size, "Client B should know 2 peers")
        assertTrue(clientB.getKnownPeers().containsKey(peerIdA), "Client B should know Client A")
        assertTrue(clientB.getKnownPeers().containsKey(peerIdC), "Client B should know Client C")
        
        // A should know B and C (B shares C's info when A connects)
        assertEquals(2, clientA.getKnownPeers().size, "Client A should know 2 peers after connecting to B")
        assertTrue(clientA.getKnownPeers().containsKey(peerIdB), "Client A should know Client B")
        assertTrue(clientA.getKnownPeers().containsKey(peerIdC), "Client A should know Client C (via B's sharing)")
        
        println("=== Step 4: Wait for SHARE_PEER messages to propagate ===")
        // Process SHARE_PEER messages that should be sent automatically
        networkSimulator.processAllMessages()
        delay(300) // Allow time for all SHARE_PEER messages to be processed
        
        println("=== Step 5: Verify all clients know each other ===")
        // Final state: all clients should know about each other
        
        // Client A should know B and C
        val peersA = clientA.getKnownPeers()
        assertEquals(2, peersA.size, "Client A should know 2 peers")
        assertTrue(peersA.containsKey(peerIdB), "Client A should know Client B")
        assertTrue(peersA.containsKey(peerIdC), "Client A should know Client C")
        
        // Client B should know A and C  
        val peersB = clientB.getKnownPeers()
        assertEquals(2, peersB.size, "Client B should know 2 peers")
        assertTrue(peersB.containsKey(peerIdA), "Client B should know Client A")
        assertTrue(peersB.containsKey(peerIdC), "Client B should know Client C")
        
        // Client C should know A and B
        val peersC = clientC.getKnownPeers()
        assertEquals(2, peersC.size, "Client C should know 2 peers")
        assertTrue(peersC.containsKey(peerIdA), "Client C should know Client A")
        assertTrue(peersC.containsKey(peerIdB), "Client C should know Client B")
        
        // Verify activity listeners received all events
        assertEquals(2, clientAActivity.peersAdded.size, "Client A should have received 2 peer added events")
        assertEquals(2, clientBActivity.peersAdded.size, "Client B should have received 2 peer added events")  
        assertEquals(2, clientCActivity.peersAdded.size, "Client C should have received 2 peer added events")
        
        println("=== Test completed: All clients successfully know each other! ===")
        
        // Print final network state
        println("Final network state:")
        println("Client A knows: ${peersA.values.map { "${it.publicIp}:${it.publicPort}" }}")
        println("Client B knows: ${peersB.values.map { "${it.publicIp}:${it.publicPort}" }}")
        println("Client C knows: ${peersC.values.map { "${it.publicIp}:${it.publicPort}" }}")
        
        // Verify message flow
        val allMessages = networkSimulator.getSentMessages()
        val handshakeMessages = allMessages.filter { it.type in setOf(NetworkMessageType.HEY_BRO, NetworkMessageType.SUP) }
        val sharePeerMessages = allMessages.filter { it.type == NetworkMessageType.SHARE_PEER }
        
        println("Total handshake messages: ${handshakeMessages.size}")
        println("Total SHARE_PEER messages: ${sharePeerMessages.size}")
        
        assertTrue(handshakeMessages.size >= 4, "Should have at least 4 handshake messages") // 2 HEY_BRO + 2 SUP
        assertTrue(sharePeerMessages.size >= 4, "Should have multiple SHARE_PEER messages for gossiping")
    }

    // Supporting classes for the test
    
    private class TestActivityListener(private val clientName: String) : Client.ActivityListener {
        val peersAdded = mutableSetOf<PeerPublicKey>()
        val peersRemoved = mutableSetOf<PeerPublicKey>()
        
        override suspend fun onPeerAdded(addedPeer: PeerNetworkInfo) {
            peersAdded.add(addedPeer.id)
            println("$clientName: Peer added - ${addedPeer.publicIp}:${addedPeer.publicPort}")
        }
        
        override suspend fun onPeerRemoved(removedPeer: PeerNetworkInfo) {
            peersRemoved.add(removedPeer.id)
            println("$clientName: Peer removed - ${removedPeer.publicIp}:${removedPeer.publicPort}")
        }
    }
    
    private class TestLogger(private val clientName: String) : LoggerInterface {
        override fun debug(tag: String, message: String) {
            println("[$clientName] DEBUG [$tag]: $message")
        }
        
        override fun info(tag: String, message: String) {
            println("[$clientName] INFO [$tag]: $message")
        }
        
        override fun warn(tag: String, message: String) {
            println("[$clientName] WARN [$tag]: $message")
        }
        
        override fun error(tag: String, message: String, throwable: Throwable?) {
            println("[$clientName] ERROR [$tag]: $message")
            throwable?.printStackTrace()
        }
    }
    
    // Simple SUP message handler to complete handshakes
    private class SupMessageHandler(
        private val configs: ConfigurationManager,
        private val logger: LoggerInterface,
    ) : NetworkMessageHandler {
        private lateinit var client: Client
        
        override fun setClientInstance(client: Client) {
            this.client = client
        }
        
        override fun canHandle(): Set<NetworkMessageType> {
            return setOf(NetworkMessageType.SUP)
        }
        
        override suspend fun handle(
            type: NetworkMessageType,
            peerNetworkInfo: PeerNetworkInfo,
            sequenceNumber: Long,
            timestamp: Long,
            payload: ByteArray
        ) {
            when (type) {
                NetworkMessageType.SUP -> {
                    try {
                        // Extract peer's public key and version from SUP payload
                        val peerPublicKey = PeerPublicKey(payload.copyOfRange(0, 512))
                        val version = payload.copyOfRange(512, 512 + Int.SIZE_BYTES).toInt()
                        
                        // Add the peer with their actual public key
                        val updatedPeerInfo = PeerNetworkInfo(
                            id = peerPublicKey,
                            publicIp = peerNetworkInfo.publicIp,
                            publicPort = peerNetworkInfo.publicPort,
                            version = version
                        )
                        
                        client.addKnownPeer(updatedPeerInfo)
                        logger.info("SupMessageHandler", "Handshake completed with peer ${updatedPeerInfo.publicIp}:${updatedPeerInfo.publicPort}")
                    } catch (e: Exception) {
                        throw InvalidPayloadException(e)
                    }
                }
                else -> throw CantHandleMessage(type)
            }
        }
    }
    
    // Test transmitter that simulates network behavior
    private class TestClientTransmitter(
        private val configs: ConfigurationManager,
        private val logger: LoggerInterface,
        private val client: Client,
        private val selfPeerInfo: PeerNetworkInfo,
        private val networkSimulator: InMemoryNetworkSimulator
    ) : ClientTransmitter {
        
        private var running = false
        
        override suspend fun transmit(type: NetworkMessageType, destination: PeerNetworkInfo, payload: ByteArray) {
            if (!running) throw Exception("Transmitter not running")
            
            // Create a network message
            val message = NetworkMessage(
                type = type,
                payload = payload,
                peerNetworkInfo = selfPeerInfo, // This message is from us
                timestamp = System.currentTimeMillis(),
                sequenceNumber = System.currentTimeMillis(), // Simple sequence numbering
                signature = ByteArray(512) // Mock signature
            )
            
            // Send through network simulator
            networkSimulator.sendMessage(message, destination)
            logger.debug("TestTransmitter", "Sent $type message to ${destination.publicIp}:${destination.publicPort}")
        }
        
        override fun start() {
            running = true
            networkSimulator.registerClient(selfPeerInfo, client)
        }
        
        override fun stop() {
            running = false
        }
        
        override fun isRunning(): Boolean = running
    }
    
    // Network simulator for testing
    private class InMemoryNetworkSimulator {
        private val clients = ConcurrentHashMap<String, Client>() // Use "IP:Port" as key
        private val messageQueue = mutableListOf<Pair<NetworkMessage, PeerNetworkInfo>>()
        private val sentMessages = mutableListOf<NetworkMessage>()
        
        fun registerClient(peerInfo: PeerNetworkInfo, client: Client) {
            val key = "${peerInfo.publicIp}:${peerInfo.publicPort}"
            clients[key] = client
        }
        
        fun sendMessage(message: NetworkMessage, destination: PeerNetworkInfo) {
            sentMessages.add(message)
            messageQueue.add(Pair(message, destination))
        }
        
        suspend fun processAllMessages() {
            // Process messages in a loop until no more messages are generated
            var rounds = 0
            while (messageQueue.isNotEmpty() && rounds < 10) { // Safety limit to prevent infinite loops
                val messagesToProcess = messageQueue.toList()
                messageQueue.clear()
                
                for ((message, destination) in messagesToProcess) {
                    val key = "${destination.publicIp}:${destination.publicPort}"
                    val targetClient = clients[key]
                    if (targetClient != null) {
                        try {
                            targetClient.handleMessage(message)
                        } catch (e: Exception) {
                            // Log error but continue processing
                            println("Error processing message: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
                rounds++
            }
        }
        
        fun getSentMessages(): List<NetworkMessage> = sentMessages.toList()
        
        fun clear() {
            clients.clear()
            messageQueue.clear()
            sentMessages.clear()
        }
    }
}
