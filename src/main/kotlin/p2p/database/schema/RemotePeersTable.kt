package p2p.database.schema

import org.jetbrains.exposed.sql.Table

object RemotePeersTable : Table("remote_peers") {
    val id = binary("id", 512)
    val score = double("score")
    val successfulConnections = integer("successful_connections").default(0)
    val failedConnections = integer("failed_connections").default(0)
    val firstSeen = long("first_seen")
    val lastSeen = long("last_seen")
    val validDownloadsCompleted = integer("valid_downloads_completed").default(0)
    val isBlocked = bool("is_blocked").default(false)
    
    override val primaryKey = PrimaryKey(id)
}