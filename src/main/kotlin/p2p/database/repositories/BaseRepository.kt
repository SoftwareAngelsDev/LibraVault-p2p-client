package p2p.database.repositories

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import p2p.database.config.DatabaseConfig
import p2p.utils.LoggerInterface

abstract class BaseRepository(
    protected val dbConfig: DatabaseConfig,
    protected val logger: LoggerInterface
) {
    protected abstract val TAG: String

    protected suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = dbConfig.database) {
            block()
        }

    suspend fun cleanDatabase(): Unit = dbQuery {
        dbConfig.cleanDatabase()
    }

    protected fun ByteArray.toHexString(): String {
        return this.joinToString("") { "%02x".format(it) }.take(16) + "..."
    }
}
