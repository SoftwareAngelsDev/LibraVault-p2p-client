package p2p.helpers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import p2p.domain.wtfs.PeerPublicKey
import p2p.network.Punishment
import p2p.network.Reward
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RemotePeerReputationManagerTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockLogger: MockLogger
    private lateinit var manager: RemotePeerReputationManager

    // Create some test peer IDs
    private val testPeer1 = PeerPublicKey(ByteArray(512) { 1 }) // Filled with 1's
    private val testPeer2 = PeerPublicKey(ByteArray(512) { 2 }) // Filled with 2's
    private val testPeer3 = PeerPublicKey(ByteArray(512) { 3 }) // Filled with 3's

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Set test mode flag for in-memory database
        System.setProperty("test.database", "true")

        mockLogger = MockLogger()

        // Setup manager with in-memory database
        // Each test gets a fresh database instance
        manager = RemotePeerReputationManager(mockLogger)
    }

    @AfterEach
    fun tearDown() {
        // Reset test mode flag
        System.clearProperty("test.database")

        Dispatchers.resetMain()
    }

    @Test
    fun testGetPeerReputationReturnsNullForUnknownPeer() = runTest {
        val result = manager.getPeerReputation(testPeer1)
        assertNull(result, "Should return null for unknown peer")
    }

    @Test
    fun testAddingNewPeerWithReputationUpdate() = runTest {
        // Add peer by applying a reward
        manager.updatePeerReputation(testPeer1, Reward.CORRECT_CHALLENGE_RESPONSE)

        // Now check the peer exists
        val storedReputation = manager.getPeerReputation(testPeer1)
        assertNotNull(storedReputation, "Peer should exist after adding")
        assertEquals(1.0 * Reward.CORRECT_CHALLENGE_RESPONSE.factor, storedReputation.score, 0.001)
    }

    @Test
    fun testGetKnownRemotePeersWithEmptyDatabase() = runTest {
        val peers = manager.getKnownRemotePeers()
        assertEquals(0, peers.size, "Should return empty map when no peers exist")
    }

    @Test
    fun testGetKnownRemotePeersWithMultiplePeers() = runTest {
        // Add multiple peers
        manager.updatePeerReputation(testPeer1, Reward.CORRECT_CHALLENGE_RESPONSE)
        manager.updatePeerReputation(testPeer2, Reward.CORRECT_CHALLENGE_RESPONSE)
        manager.updatePeerReputation(testPeer3, Punishment.WRONG_CHALLENGE_RESPONSE)

        // Test retrieving all peers
        val peers = manager.getKnownRemotePeers()
        assertEquals(3, peers.size, "Should return all added peers")
        assertTrue(peers.any { it.key == testPeer1 }, "Should contain testPeer1")
        assertTrue(peers.any { it.key == testPeer2 }, "Should contain testPeer2")
        assertTrue(peers.any { it.key == testPeer3 }, "Should contain testPeer3")
    }

    @Test
    fun testUpdatePeerReputationWithReward() = runTest {
        // Apply a reward to a new peer
        manager.updatePeerReputation(testPeer1, Reward.CORRECT_CHALLENGE_RESPONSE)

        // Verify the peer's score was updated
        val reputation = manager.getPeerReputation(testPeer1)
        assertNotNull(reputation, "Peer should exist")
        assertEquals(1.0 * Reward.CORRECT_CHALLENGE_RESPONSE.factor, reputation.score, 0.001)
    }

    @Test
    fun testUpdatePeerReputationWithPunishment() = runTest {
        // Apply a punishment to a new peer
        manager.updatePeerReputation(testPeer1, Punishment.WRONG_CHALLENGE_RESPONSE)

        // Verify the peer's score was updated
        val reputation = manager.getPeerReputation(testPeer1)
        assertNotNull(reputation, "Peer should exist")
        assertEquals(1.0 * Punishment.WRONG_CHALLENGE_RESPONSE.factor, reputation.score, 0.001)
    }

    @Test
    fun testRegisterSuccessfulConnectionIncrementsCounter() = runTest {
        // First connection should create new peer
        manager.registerSuccessfulConnection(testPeer1)

        // Verify peer was created with successful connection
        var peer = manager.getPeerReputation(testPeer1)
        assertNotNull(peer, "Peer should be created")
        assertEquals(1, peer.successfulConnections, "Should have one successful connection")

        // Register another successful connection
        manager.registerSuccessfulConnection(testPeer1)

        // Verify counter was incremented
        peer = manager.getPeerReputation(testPeer1)
        assertNotNull(peer, "Peer should still exist")
        assertEquals(2, peer.successfulConnections, "Should have two successful connections")
    }

    @Test
    fun testRegisterFailedConnectionIncrementsCounter() = runTest {
        // First failed connection should create new peer
        manager.registerFailedConnection(testPeer1)

        // Verify peer was created with failed connection
        var peer = manager.getPeerReputation(testPeer1)
        assertNotNull(peer, "Peer should be created")
        assertEquals(1, peer.failedConnections, "Should have one failed connection")

        // Register another failed connection
        manager.registerFailedConnection(testPeer1)

        // Verify counter was incremented
        peer = manager.getPeerReputation(testPeer1)
        assertNotNull(peer, "Peer should still exist")
        assertEquals(2, peer.failedConnections, "Should have two failed connections")
    }

    @Test
    fun testApplyingMultipleReputationUpdates() = runTest {
        // Initial peer with default score (1.0)
        manager.registerSuccessfulConnection(testPeer1)

        // Apply several updates
        manager.updatePeerReputation(testPeer1, Reward.CORRECT_CHALLENGE_RESPONSE)  // 1.0 * 1.01 = 1.01
        manager.updatePeerReputation(testPeer1, Reward.CORRECT_CHALLENGE_RESPONSE)  // 1.01 * 1.01 = 1.0201
        manager.updatePeerReputation(testPeer1, Punishment.WRONG_CHALLENGE_RESPONSE)  // 1.0201 * 0.5 = 0.51005

        // Verify final score
        val peer = manager.getPeerReputation(testPeer1)
        assertNotNull(peer, "Peer should still exist")
        assertEquals(0.51005, peer.score, 0.0001)
    }

    @Test
    fun testLastSeenIsUpdatedOnAllOperations() = runTest {
        // Create initial peer
        manager.registerSuccessfulConnection(testPeer1)
        val initialTime = manager.getPeerReputation(testPeer1)!!.lastSeen

        // Wait a bit
        Thread.sleep(10)

        // Update with reward
        manager.updatePeerReputation(testPeer1, Reward.CORRECT_CHALLENGE_RESPONSE)
        val afterRewardTime = manager.getPeerReputation(testPeer1)!!.lastSeen
        assertTrue(afterRewardTime > initialTime, "lastSeen should be updated after reward")

        // Wait a bit more
        Thread.sleep(10)

        // Update with punishment
        manager.updatePeerReputation(testPeer1, Punishment.WRONG_CHALLENGE_RESPONSE)
        val afterPunishmentTime = manager.getPeerReputation(testPeer1)!!.lastSeen
        assertTrue(afterPunishmentTime > afterRewardTime, "lastSeen should be updated after punishment")

        // Wait again
        Thread.sleep(10)

        // Register failed connection
        manager.registerFailedConnection(testPeer1)
        val afterFailedTime = manager.getPeerReputation(testPeer1)!!.lastSeen
        assertTrue(afterFailedTime > afterPunishmentTime, "lastSeen should be updated after failed connection")
    }

    @Test
    fun testBlockAndUnblockPeer() = runTest {
        // Initially peer should not be blocked
        assertFalse(manager.isPeerBlocked(testPeer1), "New peer should not be blocked by default")

        // Block the peer
        manager.blockPeer(testPeer1)

        // Verify peer is blocked
        assertTrue(manager.isPeerBlocked(testPeer1), "Peer should be blocked after blockPeer()")

        // Get blocked peers list
        val blockedPeers = manager.getBlockedPeers()
        assertEquals(1, blockedPeers.size, "Should have one blocked peer")
        assertEquals(blockedPeers[0].remotePeerId, testPeer1, "Blocked peer should be testPeer1")

        // Unblock the peer
        manager.unblockPeer(testPeer1)

        // Verify peer is unblocked
        assertFalse(manager.isPeerBlocked(testPeer1), "Peer should be unblocked after unblockPeer()")

        // Get blocked peers list again
        val updatedBlockedPeers = manager.getBlockedPeers()
        assertEquals(0, updatedBlockedPeers.size, "Should have no blocked peers")
    }
}