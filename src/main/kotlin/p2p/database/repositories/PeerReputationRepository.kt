package p2p.database.repositories

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import p2p.database.config.DatabaseConfig
import p2p.database.schema.RemotePeersTable
import p2p.domain.RemotePeerId
import p2p.domain.RemotePeerReputation
import p2p.domain.wtfs.PeerPublicKey
import p2p.utils.LoggerInterface

class PeerReputationRepository(
    private val dbConfig: DatabaseConfig,
    private val logger: LoggerInterface
) {
    companion object {
        private const val TAG = "PeerReputationRepository"
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = dbConfig.database) {
            block()
        }

    private fun resultRowToReputation(row: ResultRow): RemotePeerReputation {
        return RemotePeerReputation(
            remotePeerId = PeerPublicKey(row[RemotePeersTable.id]),
            score = row[RemotePeersTable.score],
            successfulConnections = row[RemotePeersTable.successfulConnections],
            failedConnections = row[RemotePeersTable.failedConnections],
            firstSeen = row[RemotePeersTable.firstSeen],
            lastSeen = row[RemotePeersTable.lastSeen],
            validDownloadsCompleted = row[RemotePeersTable.validDownloadsCompleted],
            isBlocked = row[RemotePeersTable.isBlocked]
        )
    }

    suspend fun getAllPeers(): Map<RemotePeerId, RemotePeerReputation> = dbQuery {
        RemotePeersTable.selectAll().associate { row ->
            RemotePeerId(row[RemotePeersTable.id]) to resultRowToReputation(row)
        }
    }

    suspend fun getPeerReputation(peerId: RemotePeerId): RemotePeerReputation? = dbQuery {
        RemotePeersTable.selectAll().where { RemotePeersTable.id eq peerId.toByteArray() }
            .map(::resultRowToReputation)
            .singleOrNull()
    }

    suspend fun insertPeerReputation(reputation: RemotePeerReputation): RemotePeerReputation = dbQuery {
        RemotePeersTable.insert {
            it[id] = reputation.remotePeerId.toByteArray()
            it[score] = reputation.score
            it[successfulConnections] = reputation.successfulConnections
            it[failedConnections] = reputation.failedConnections
            it[firstSeen] = reputation.firstSeen
            it[lastSeen] = reputation.lastSeen
            it[validDownloadsCompleted] = reputation.validDownloadsCompleted
            it[isBlocked] = reputation.isBlocked
        }
        reputation
    }

    suspend fun savePeerReputation(reputation: RemotePeerReputation): Unit = dbQuery {
        RemotePeersTable.update({ RemotePeersTable.id eq reputation.remotePeerId.toByteArray() }) {
            it[score] = reputation.score
            it[successfulConnections] = reputation.successfulConnections
            it[failedConnections] = reputation.failedConnections
            it[lastSeen] = reputation.lastSeen
            it[validDownloadsCompleted] = reputation.validDownloadsCompleted
            it[isBlocked] = reputation.isBlocked
        }
    }

    suspend fun incrementSuccessfulConnections(peerId: RemotePeerId, currentTime: Long): Unit = dbQuery {
        val currentPeer = RemotePeersTable.selectAll()
            .where { RemotePeersTable.id eq peerId.toByteArray() }
            .singleOrNull()

        if (currentPeer != null) {
            val currentCount = currentPeer[RemotePeersTable.successfulConnections]

            RemotePeersTable.update({ RemotePeersTable.id eq peerId.toByteArray() }) {
                it[successfulConnections] = currentCount + 1
                it[lastSeen] = currentTime
            }
        }
    }

    suspend fun incrementFailedConnections(peerId: RemotePeerId, currentTime: Long): Unit = dbQuery {
        val currentPeer = RemotePeersTable.selectAll()
            .where { RemotePeersTable.id eq peerId.toByteArray() }
            .singleOrNull()

        if (currentPeer != null) {
            val currentCount = currentPeer[RemotePeersTable.failedConnections]

            RemotePeersTable.update({ RemotePeersTable.id eq peerId.toByteArray() }) {
                it[failedConnections] = currentCount + 1
                it[lastSeen] = currentTime
            }
        }
    }

    suspend fun blockPeer(peerId: RemotePeerId, currentTime: Long): Unit = dbQuery {
        RemotePeersTable.update({ RemotePeersTable.id eq peerId.toByteArray() }) {
            it[isBlocked] = true
            it[lastSeen] = currentTime
        }

        logger.info(TAG, "Peer blocked: $peerId")
    }

    suspend fun unblockPeer(peerId: RemotePeerId, currentTime: Long): Unit = dbQuery {
        RemotePeersTable.update({ RemotePeersTable.id eq peerId.toByteArray() }) {
            it[isBlocked] = false
            it[lastSeen] = currentTime
        }

        logger.info(TAG, "Peer unblocked: $peerId")
    }

    suspend fun getBlockedPeers(): List<RemotePeerReputation> = dbQuery {
        RemotePeersTable.selectAll()
            .where { RemotePeersTable.isBlocked eq true }
            .map(::resultRowToReputation)
    }

    suspend fun cleanDatabase(): Unit = dbQuery {
        dbConfig.cleanDatabase()
    }

    private fun ByteArray.toHexString(): String {
        return this.joinToString("") { "%02x".format(it) }.take(16) + "..."
    }
}