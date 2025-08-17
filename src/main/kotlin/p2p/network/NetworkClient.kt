@file:OptIn(ExperimentalAtomicApi::class)

package p2p.network

import kotlinx.coroutines.*
import p2p.domain.Chunk
import p2p.domain.RemotePeer
import p2p.domain.RemotePeerMetadata
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
    val metadata: AtomicReference<RemotePeerMetadata>,
    val host: PeerIpAddress,
    val port: PeerPort,
)

class NetworkClient {
    companion object {
        const val MAX_NUMBER_OF_CHUNK_BYTE_CHALLENGES = 5
        const val MINIMUM_REPUTATION_TO_BE_PEER = 0.1
        private const val TAG = "NetworkClient"
    }

    private val knownPeers = ConcurrentHashMap<RemotePeer, PeerNetworkInfo>()

    suspend fun whoHas(chunk: Chunk, timeoutSeconds: Int): Collection<RemotePeer> = withContext(Dispatchers.IO) {
        Logger.info(TAG, "Looking for peers who have chunk ${chunk.metadata.index} of file ${chunk.fileId}")
        val chunkFile = File(chunk.path)
        val doWeHaveTheFile = chunkFile.exists()
        if (!doWeHaveTheFile) {
            Logger.debug(TAG, "Local chunk file not found at ${chunk.path}")
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
                            Logger.error(TAG, "Error communicating with peer ${peerInfo.host}:${peerInfo.port}", e)
                            incrementPeerFailedConnections(peer)
                            errors[peerInfo] = e
                            return@async
                        }

                        incrementPeerSuccessfulConnections(peer)

                        if (response != null && response.haveChunk) {
                            if (response.challengeRespectiveBytes == challengeRespectiveBytes) {
                                ackPeers[peer] = Unit
                                updatePeerReputation(peer, Reward.CORRECT_CHALLENGE_RESPONSE)
                            } else {
                                updatePeerReputation(peer, Punishment.WRONG_CHALLENGE_RESPONSE)
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

    private fun getEligiblePeers(): Map<RemotePeer, PeerNetworkInfo> {
        val eligiblePeers = knownPeers
            .filter { (_, peerInfo) -> peerInfo.metadata.get().reputation >= MINIMUM_REPUTATION_TO_BE_PEER }
        return eligiblePeers
    }

    private fun incrementPeerSuccessfulConnections(peer: RemotePeer) {
        val peerInfo = knownPeers[peer] ?: return
        val oldInfo = peerInfo.metadata.get()

        peerInfo.metadata.set(
            oldInfo.copy(
                successfulConnections = oldInfo.successfulConnections + 1,
                lastSeen = System.currentTimeMillis()
            )
        )
    }

    private fun incrementPeerFailedConnections(peer: RemotePeer) {
        val peerInfo = knownPeers[peer] ?: return
        val oldInfo = peerInfo.metadata.get()

        peerInfo.metadata.set(
            oldInfo.copy(
                failedConnections = oldInfo.failedConnections + 1,
            )
        )
    }

    private fun updatePeerReputation(peer: RemotePeer, p: Punishment) {
        val peerInfo = knownPeers[peer] ?: return
        val oldInfo = peerInfo.metadata.get()

        val newReputation = oldInfo.reputation * p.factor
        peerInfo.metadata.set(
            oldInfo.copy(reputation = newReputation, lastSeen = System.currentTimeMillis())
        )

        Logger.debug(
            TAG,
            "Decreased peer reputation for ${peerInfo.host}:${peerInfo.port}: ${oldInfo.reputation} -> $newReputation (${p.javaClass.simpleName})"
        )
    }

    private fun updatePeerReputation(peer: RemotePeer, r: Reward) {
        val peerInfo = knownPeers[peer] ?: return
        val oldInfo = peerInfo.metadata.get()

        val newReputation = oldInfo.reputation * r.factor
        peerInfo.metadata.set(
            oldInfo.copy(reputation = newReputation, lastSeen = System.currentTimeMillis())
        )

        Logger.debug(
            TAG,
            "Increased peer reputation for ${peerInfo.host}:${peerInfo.port}: ${oldInfo.reputation} -> $newReputation (${r.javaClass.simpleName})"
        )
    }

    private suspend fun askPeer(
        peer: RemotePeer,
        request: DoYouHaveThisChunkRequest,
        timeoutSeconds: Int
    ): NetworkResponse? = withContext(Dispatchers.IO) {
        TODO()
    }

}