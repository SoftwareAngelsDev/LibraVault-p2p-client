package p2p.database.schema

import org.jetbrains.exposed.sql.Table
import p2p.domain.wtfs.PeerPublicKey
import p2p.helpers.FilePodMerkleTreeHelper

object PodsTable : Table("pods") {
    val owner = binary("owner", PeerPublicKey.SIZE_BYTES)
    val number = integer("number")
    val merkleHashRoot = binary("merkle_hash_root", FilePodMerkleTreeHelper.MERKLE_HASH_ROOT_SIZE_BYTES)
    val updatedAt = long("updated_at")
    val ownerSignature = binary("owner_signature", PeerPublicKey.SIZE_BYTES) // Same size as RSA 4096 signature

    override val primaryKey = PrimaryKey(owner, number)
}