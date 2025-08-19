package p2p.helpers

import p2p.domain.ChunkIndex
import p2p.domain.FileId
import p2p.domain.FileUserRelativeFilePath
import p2p.network.NetworkClient

class ChunkDistributor(
    private val client: NetworkClient,
    private val chunkCreator: ChunkCreator,
) {
    fun propagateUserRelativePathUpdate(fileId: FileId, newPath: FileUserRelativeFilePath) {
        TODO()
    }

    fun propagateChunks(fileId: FileId) {
        TODO()
    }

    suspend fun pollExistingChunkReplicas(fileId: FileId, timeoutSeconds: Int = 30): Map<ChunkIndex, Int> {
        val chunks = chunkCreator.getChunks(fileId)
        return chunks.associate { chunk ->
            chunk.metadata.index to client.whoHas(chunk, timeoutSeconds).size
        }
    }
}