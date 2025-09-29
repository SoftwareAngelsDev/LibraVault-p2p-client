package p2p.network.client.messages.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

class LongTransmissionProtocol(
    private val message: ByteArray,
    private val transmitter: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val MAX_CHUNK_BYTES = Short.MAX_VALUE
        private const val LOOP_INTERVAL_MILLIS = 10L
    }

    private val confirmedChunks = BitSet()
    private val confirmedCount = AtomicInteger(0)

    suspend fun process(timeoutSecs: Short = 600) {
        withTimeoutOrNull(timeoutSecs * 1000L) {
            val chunkNumber = ceil(message.size / MAX_CHUNK_BYTES.toDouble()).toInt()

            var i = 0
            while (confirmedCount.get() < chunkNumber) {
                synchronized(confirmedChunks) {
                    if (confirmedChunks.get(i)) {
                        // Chunk already sent and confirmed, skip
                        i = (i + 1) % chunkNumber
                        continue
                    }
                }

                val startIndex = i * MAX_CHUNK_BYTES
                val endIndex = kotlin.math.min(startIndex + MAX_CHUNK_BYTES, message.size)
                val chunk = message.sliceArray(startIndex until endIndex)

                transmitter(chunk, i)
                i = (i + 1) % chunkNumber

                delay(LOOP_INTERVAL_MILLIS)
            }
        }
    }

    fun confirmChunk(chunkNumber: Int) {
        val totalChunks = ceil(message.size / MAX_CHUNK_BYTES.toDouble()).toInt()
        if (chunkNumber !in 0..<totalChunks) {
            return // Ignore invalid chunk numbers
        }

        synchronized(confirmedChunks) {
            if (!confirmedChunks.get(chunkNumber)) {
                confirmedChunks.set(chunkNumber)
                confirmedCount.incrementAndGet()
            }
        }
    }
}