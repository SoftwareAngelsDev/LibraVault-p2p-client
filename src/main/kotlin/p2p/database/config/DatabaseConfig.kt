package p2p.database.config

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import p2p.database.schema.RemotePeersTable
import p2p.database.schema.PodsTable
import p2p.utils.LoggerInterface
import java.sql.Connection
import java.util.*

class DatabaseConfig(private val logger: LoggerInterface) {

    companion object {
        private const val TAG = "DatabaseConfig"
        private const val DB_NAME = "libravault.db"
    }
    
    val database: Database
    
    init {
        val isTest = System.getProperty("test.database") == "true"
        
        database = if (isTest) {
            val uniqueDbName = "mem:test_${UUID.randomUUID()}"
            Database.connect(
                url = "jdbc:h2:$uniqueDbName;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        } else {
            Database.connect(
                url = "jdbc:sqlite:$DB_NAME",
                driver = "org.sqlite.JDBC"
            )
        }
        
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        
        transaction(database) {
            SchemaUtils.create(RemotePeersTable, PodsTable)
            logger.info(TAG, "Database schema created or verified")
        }
    }
    
    fun cleanDatabase() {
        if (System.getProperty("test.database") == "true") {
            transaction(database) {
                SchemaUtils.drop(RemotePeersTable, PodsTable)
                SchemaUtils.create(RemotePeersTable, PodsTable)
                logger.info(TAG, "Test database cleaned")
            }
        }
    }
}