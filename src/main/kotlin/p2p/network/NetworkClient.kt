@file:OptIn(ExperimentalAtomicApi::class)

package p2p.network

import kotlinx.coroutines.*
import p2p.domain.Chunk
import p2p.domain.RemotePeer
import p2p.domain.RemotePeerId
import p2p.domain.RemotePeerReputation
import p2p.helpers.RemotePeerReputationManager
import p2p.network.messages.DoYouHaveThisChunkRequest
import p2p.network.messages.DoYouHaveThisChunkResponse
import p2p.network.messages.NetworkResponse
import p2p.utils.Logger
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

typealias PeerIpAddress = AtomicReference<String>
typealias PeerPort = AtomicInt

private data class PeerNetworkInfo(
    val peer: RemotePeer,
    val metadata: AtomicReference<RemotePeerReputation>,
    val host: PeerIpAddress,
    val port: PeerPort,
)

class NetworkClient(
    private val logger: Logger,
    private val reputationManager: RemotePeerReputationManager,
) {
    companion object {
        const val MAX_NUMBER_OF_CHUNK_BYTE_CHALLENGES = 5
        const val MINIMUM_REPUTATION_TO_BE_PEER = 0.1
        private const val TAG = "NetworkClient"
    }

    suspend fun whoHas(chunk: Chunk, timeoutSeconds: Int): Collection<RemotePeer> = withContext(Dispatchers.IO) {
        logger.info(TAG, "Looking for peers who have chunk ${chunk.metadata.index} of file ${chunk.fileId}")
        val chunkFile = File(chunk.path)
        val doWeHaveTheFile = chunkFile.exists()
        if (!doWeHaveTheFile) {
            logger.debug(TAG, "Local chunk file not found at ${chunk.path}")
        }

        val challengePositions = if (doWeHaveTheFile) {
            val maxChallengePosition = File(chunk.path).length().toInt()
            val rnd = SecureRandom()
            (0 until MAX_NUMBER_OF_CHUNK_BYTE_CHALLENGES)
                .map {
                    rnd.nextInt(maxChallengePosition)
                }
                .distinct()
                .sorted()
        } else {
            emptyList()
        }

        // Proof of Retrievability - PoR
        val challengeRespectiveBytes = chunkFile.inputStream().use { stream ->
            // Now we have to read the bytes at the challenge positions
            var cursor = 0
            challengePositions.map { byteIndex ->
                // Drop all bytes until we reach the challenge position
                while (cursor < byteIndex && stream.available() > 0) {
                    stream.read()
                    cursor++
                }

                if (stream.available() <= 0) {
                    null
                } else {
                    cursor++
                    stream.read().toUByte()
                }
            }
        }

        val request = DoYouHaveThisChunkRequest(
            fileId = chunk.fileId,
            chunkIndex = chunk.metadata.index,
            challengePositions = challengePositions,
        )

        val ackPeers = ConcurrentHashMap<RemotePeer, Unit>()
        val errors = ConcurrentHashMap<PeerNetworkInfo, Throwable>()
        val eligiblePeers = getEligiblePeers().ifEmpty { return@withContext emptyList() }

        withTimeoutOrNull(timeoutSeconds * 2 * 1000L) {
            coroutineScope {
                eligiblePeers.map { (peer, peerInfo) ->
                    async {
                        val response = try {
                            askPeer(peer, request, timeoutSeconds) as DoYouHaveThisChunkResponse?
                        } catch (_: InterruptedException) {
                            // ignore
                            return@async
                        } catch (e: Throwable) {
                            // Track errors with the specific peer
                            logger.error(TAG, "Error communicating with peer ${peerInfo.host}:${peerInfo.port}", e)
                            reputationManager.registerFailedConnection(peer.id)
                            errors[peerInfo] = e
                            return@async
                        }

                        reputationManager.registerSuccessfulConnection(peer.id)

                        if (response != null && response.haveChunk) {
                            if (response.challengeRespectiveBytes == challengeRespectiveBytes) {
                                ackPeers[peer] = Unit
                                reputationManager.updatePeerReputation(peer.id, Reward.CORRECT_CHALLENGE_RESPONSE)
                            } else {
                                reputationManager.updatePeerReputation(peer.id, Punishment.WRONG_CHALLENGE_RESPONSE)
                            }
                        }
                    }
                }.awaitAll()

                // After all completions, check if all peers failed
                if (errors.size == eligiblePeers.size) {
                    val errorMessage = buildString {
                        append("All ${eligiblePeers.size} peers failed to respond properly:\n")
                        errors.forEach { (peerInfo, error) ->
                            append("- ${peerInfo.host}:${peerInfo.port}: ${error::class.simpleName}: ${error.message}\n")
                        }
                        append("\n\n")
                        append("Please check your network connection and try again.")
                    }

                    throw IOException(errorMessage, errors.values.firstOrNull())
                }
            }
        }

        return@withContext ackPeers.keys
    }

    private fun getEligiblePeers(): Map<RemotePeerId, RemotePeerReputation> {
        return reputationManager
            .getKnownRemotePeers()
            .filter { (_, peerRep) -> peerRep.score >= MINIMUM_REPUTATION_TO_BE_PEER }
    }

    private suspend fun askPeer(
        peer: RemotePeer,
        request: DoYouHaveThisChunkRequest,
        timeoutSeconds: Int
    ): NetworkResponse? = withContext(Dispatchers.IO) {
        TODO()
    }
}