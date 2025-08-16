package p2p.network.messages

import p2p.domain.ChunkIndex
import p2p.domain.FileId
import kotlin.reflect.KClass

interface NetworkRequest {
    fun respectiveResponse(): KClass<out NetworkResponse>?
}

interface NetworkResponse

class DoYouHaveThisChunkRequest(
    val fileId: FileId,
    val chunkIndex: ChunkIndex,
    val challengePositions: List<Int>, // If we also have the file, we can challenge the peer to tell us the bytes in these positions as a proof
) : NetworkRequest {
    override fun respectiveResponse() = DoYouHaveThisChunkResponse::class
}

class DoYouHaveThisChunkResponse(
    val fileId: FileId,
    val chunkIndex: ChunkIndex,
    val haveChunk: Boolean,
    val challengeRespectiveBytes: List<UByte?>?,
) : NetworkResponse