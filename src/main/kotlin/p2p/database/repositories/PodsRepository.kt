package p2p.database.repositories

import org.jetbrains.exposed.sql.*
import p2p.database.config.DatabaseConfig
import p2p.database.schema.PodsTable
import p2p.domain.wtfs.PeerPublicKey
import p2p.domain.wtfs.PodMetadata
import p2p.utils.LoggerInterface

class PodsRepository(
    dbConfig: DatabaseConfig,
    logger: LoggerInterface
) : BaseRepository(dbConfig, logger) {

    override val TAG = "PodsRepository"

    private fun resultRowToPodMetadata(row: ResultRow): PodMetadata {
        return PodMetadata(
            owner = PeerPublicKey(row[PodsTable.owner]),
            number = row[PodsTable.number],
            merkleHashRoot = row[PodsTable.merkleHashRoot],
            updatedAt = row[PodsTable.updatedAt],
            ownerSignature = row[PodsTable.ownerSignature]
        )
    }

    suspend fun getAllPods(): List<PodMetadata> = dbQuery {
        PodsTable.selectAll().map(::resultRowToPodMetadata)
    }

    suspend fun getPodsByOwner(owner: PeerPublicKey): List<PodMetadata> = dbQuery {
        PodsTable.selectAll()
            .where { PodsTable.owner eq owner.toByteArray() }
            .map(::resultRowToPodMetadata)
    }

    suspend fun upsertPod(podMetadata: PodMetadata): PodMetadata = dbQuery {
        // Check if pod exists
        val existingPod = PodsTable.selectAll()
            .where { (PodsTable.owner eq podMetadata.owner.toByteArray()) and (PodsTable.number eq podMetadata.number) }
            .singleOrNull()

        if (existingPod != null) {
            val existingUpdatedAt = existingPod[PodsTable.updatedAt]

            if (podMetadata.updatedAt > existingUpdatedAt) {
                // Update existing pod only if incoming is newer
                PodsTable.update({
                    (PodsTable.owner eq podMetadata.owner.toByteArray()) and (PodsTable.number eq podMetadata.number)
                }) {
                    it[merkleHashRoot] = podMetadata.merkleHashRoot
                    it[updatedAt] = podMetadata.updatedAt
                    it[ownerSignature] = podMetadata.ownerSignature
                }
                logger.info(
                    TAG,
                    "Pod updated: owner=${podMetadata.owner}, number=${podMetadata.number}, updatedAt=${podMetadata.updatedAt}"
                )
                podMetadata
            } else {
                logger.info(
                    TAG,
                    "Pod update skipped (older/same timestamp): owner=${podMetadata.owner}, number=${podMetadata.number}, incoming=${podMetadata.updatedAt}, existing=${existingUpdatedAt}"
                )
                resultRowToPodMetadata(existingPod)
            }
        } else {
            // Insert new pod
            PodsTable.insert {
                it[owner] = podMetadata.owner.toByteArray()
                it[number] = podMetadata.number
                it[merkleHashRoot] = podMetadata.merkleHashRoot
                it[updatedAt] = podMetadata.updatedAt
                it[ownerSignature] = podMetadata.ownerSignature
            }
            logger.info(
                TAG,
                "Pod inserted: owner=${podMetadata.owner}, number=${podMetadata.number}, updatedAt=${podMetadata.updatedAt}"
            )
            podMetadata
        }
    }
}
