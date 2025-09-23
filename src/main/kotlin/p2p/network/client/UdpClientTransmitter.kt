package p2p.network.client

import kotlinx.coroutines.*
import p2p.domain.KEY_SIZE_BYTES
import p2p.domain.PeerSignature
import p2p.domain.SIGNATURE_SIZE_BYTES
import p2p.helpers.ConfigurationManager
import p2p.network.PeerNetworkInfo
import p2p.network.client.messages.NetworkMessage
import p2p.network.client.messages.NetworkMessageType
import p2p.utils.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class UdpClientTransmitter(
    private val configs: ConfigurationManager,
    private val logger: LoggerInterface,
    private val client: Client,
) : ClientTransmitter {

    // UDP socket and networking
    private var udpSocket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var listeningJob: Job? = null
    private val sequenceNumber = AtomicLong(0)

    // Message protocol constants
    companion object {
        // Recovery settings
        private const val SOCKET_RECOVERY_GRACE_PERIOD_MS = 3000L
        private const val MAX_RECOVERY_ATTEMPTS = 2

        // Message format
        private val MESSAGE_TYPE_SIZE = Int.SIZE_BYTES // Int for enum ordinal
        private val TIMESTAMP_SIZE = Long.SIZE_BYTES   // Long
        private val SEQUENCE_SIZE = Long.SIZE_BYTES    // Long
        private val SIGNATURE_SIZE = SIGNATURE_SIZE_BYTES // RSA 4096 signature
        private val PEER_ID_SIZE = KEY_SIZE_BYTES   // RSA 4096 public key
        private val PAYLOAD_SIZE_SIZE = Int.SIZE_BYTES // Int for payload length
        private const val VERSION = 1
    }

    override suspend fun transmit(type: NetworkMessageType, destination: PeerNetworkInfo, payload: ByteArray) {
        transmitWithRecovery(type, destination, payload)
    }

    private suspend fun transmitWithRecovery(
        type: NetworkMessageType,
        destination: PeerNetworkInfo,
        payload: ByteArray
    ) {
        // Check preconditions and throw exceptions instead of returning
        if (!isRunning.get()) {
            val errorMsg = "Cannot transmit message - transmitter is not running"
            logger.error("UdpClientTransmitter", errorMsg)
            throw TransmitterNotRunningException(errorMsg)
        }

        repeat(MAX_RECOVERY_ATTEMPTS + 1) { attempt ->
            val socket = udpSocket ?: run {
                if (attempt == MAX_RECOVERY_ATTEMPTS) {
                    val errorMsg =
                        "Cannot transmit message - UDP socket is not initialized after $MAX_RECOVERY_ATTEMPTS recovery attempts"
                    logger.error("UdpClientTransmitter", errorMsg)
                    throw SocketNotInitializedException(errorMsg)
                }

                // Attempt regenerative recovery
                logger.warn(
                    "UdpClientTransmitter",
                    "Socket not initialized, attempting regenerative recovery (attempt ${attempt + 1}/${MAX_RECOVERY_ATTEMPTS + 1})"
                )

                // Grace period to avoid flooding
                delay(SOCKET_RECOVERY_GRACE_PERIOD_MS)

                // Try to recover the socket
                if (!attemptSocketRecovery()) {
                    val errorMsg = "Socket recovery failed - cannot reinitialize UDP socket"
                    logger.error("UdpClientTransmitter", errorMsg)
                    throw SocketNotInitializedException(errorMsg)
                }

                logger.info("UdpClientTransmitter", "Socket recovery successful, retrying transmission")
                udpSocket!! // We know it's not null after successful recovery
            }

            try {
                // Create message with signature placeholder first
                val timestamp = System.currentTimeMillis()
                val sequenceNum = sequenceNumber.incrementAndGet()

                // Create actual signature for the message data
                val messageData = mergeByteArrays(
                    type.ordinal.toByteArray(),
                    timestamp.toByteArray(),
                    sequenceNum.toByteArray(),
                    payload
                )
                val signature = createSignature(messageData)

                // Create final message with actual signature
                val finalMessage = NetworkMessage(type, payload, destination, timestamp, sequenceNum, signature)

                // Serialize message to bytes - this is non-blocking
                val messageBytes = try {
                    serializeMessage(finalMessage)
                } catch (e: Exception) {
                    val errorMsg =
                        "Failed to serialize message for transmission to ${destination.publicIp}:${destination.publicPort}"
                    logger.error("UdpClientTransmitter", "$errorMsg - ${e.message}")
                    throw MessageSerializationException(errorMsg, e)
                }

                // Create UDP packet and send - non-blocking with coroutine context switch
                val address = InetAddress.getByName(destination.publicIp)
                val packet = DatagramPacket(
                    messageBytes,
                    messageBytes.size,
                    address,
                    destination.publicPort.toInt()
                )

                // Use IO dispatcher for non-blocking network operation
                withContext(Dispatchers.IO) {
                    socket.send(packet)
                }

                logger.info(
                    "UdpClientTransmitter",
                    "Message sent to ${destination.publicIp}:${destination.publicPort} - Type: $type, Size: ${messageBytes.size} bytes"
                )

                return // Success - exit retry loop

            } catch (e: ClientTransmitterException) {
                // Re-throw our custom exceptions (don't retry these)
                throw e
            } catch (e: Exception) {
                // Wrap any other exceptions in our custom exception
                val errorMsg = "Failed to transmit message to ${destination.publicIp}:${destination.publicPort}"
                logger.error("UdpClientTransmitter", "$errorMsg - ${e.message}")
                throw TransmissionFailedException(errorMsg, e)
            }
        }
    }

    /**
     * Attempts to recover the UDP socket by reinitializing it.
     * This is called when SocketNotInitializedException occurs.
     *
     * @return true if recovery was successful, false otherwise
     */
    private fun attemptSocketRecovery(): Boolean {
        return try {
            logger.info("UdpClientTransmitter", "Attempting to recover UDP socket...")

            // Clean up existing socket if any
            udpSocket?.close()
            udpSocket = null

            // Create new socket
            udpSocket = DatagramSocket(configs.udpPort).apply {
                reuseAddress = true
                soTimeout = 0 // Non-blocking
            }

            // Restart listening if we have a message handler
            listeningJob?.cancel()
            listeningJob = CoroutineScope(Dispatchers.IO).launch {
                logger.info("UdpClientTransmitter", "Restarted UDP listening after socket recovery")
                listenForMessages()
            }

            logger.info("UdpClientTransmitter", "Socket recovery completed successfully")
            true

        } catch (e: Exception) {
            logger.error("UdpClientTransmitter", "Socket recovery failed: ${e.message}")
            udpSocket?.close()
            udpSocket = null
            false
        }
    }

    override fun start() {
        if (isRunning.get()) {
            logger.warn("UdpClientTransmitter", "Transmitter is already running")
            return
        }

        try {
            // Create and bind UDP socket
            udpSocket = DatagramSocket(configs.udpPort).apply {
                reuseAddress = true
                soTimeout = 0 // Non-blocking
            }

            isRunning.set(true)

            // Start listening for incoming messages in a coroutine
            listeningJob = CoroutineScope(Dispatchers.IO).launch {
                logger.info("UdpClientTransmitter", "UDP server started on port ${configs.udpPort}")
                listenForMessages()
            }

            logger.info("UdpClientTransmitter", "Transmitter started successfully on UDP port ${configs.udpPort}")

        } catch (e: Exception) {
            logger.error("UdpClientTransmitter", "Failed to start transmitter: ${e.message}")
            isRunning.set(false)
            udpSocket?.close()
            udpSocket = null
        }
    }

    override fun stop() {
        if (!isRunning.get()) {
            logger.warn("UdpClientTransmitter", "Transmitter is already stopped")
            return
        }

        logger.info("UdpClientTransmitter", "Stopping transmitter...")

        // Set running flag to false to signal shutdown
        isRunning.set(false)

        // Cancel the listening job
        listeningJob?.cancel()
        listeningJob = null

        // Close the UDP socket
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            logger.error("UdpClientTransmitter", "Error closing UDP socket: ${e.message}")
        }
        udpSocket = null

        // Reset sequence number and message handler
        sequenceNumber.set(0)

        logger.info("UdpClientTransmitter", "Transmitter stopped successfully")
    }

    override fun isRunning(): Boolean {
        return isRunning.get()
    }

    private suspend fun listenForMessages() {
        val socket = udpSocket ?: return
        val buffer = ByteArray(65536) // 64KB buffer for UDP packets

        while (isRunning.get() && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)

                withContext(Dispatchers.IO) {
                    socket.receive(packet)
                }

                // Extract actual message bytes
                val messageBytes = packet.data.sliceArray(0 until packet.length)

                logger.debug(
                    "UdpClientTransmitter",
                    "Received UDP packet from ${packet.address}:${packet.port}, size: ${packet.length} bytes"
                )

                // Deserialize and handle message
                try {
                    val senderAddress = packet.address.hostAddress
                    val senderPort = packet.port.toShort()
                    val message = deserializeMessage(messageBytes, senderAddress, senderPort)

                    // Skip processing if message is not intended for us
                    @Suppress("FoldInitializerAndIfToElvis")
                    if (message == null) {
                        continue
                    }

                    // Validate message signature (placeholder for now)
                    // TODO: Implement proper signature verification

                    // Handle the message using the provided handler
                    client.handleMessage(message)
                } catch (e: Exception) {
                    logger.error(
                        "UdpClientTransmitter",
                        "Failed to deserialize message from ${packet.address}:${packet.port} - ${e.message}"
                    )
                }

            } catch (e: Exception) {
                if (isRunning.get() && !socket.isClosed) {
                    logger.error("UdpClientTransmitter", "Error receiving UDP packet: ${e.message}")
                    delay(100) // Brief delay before retrying
                }
            }
        }

        logger.info("UdpClientTransmitter", "Stopped listening for UDP messages")
    }

    /**
     * Serializes a NetworkMessage to bytes for network transmission.
     * Message format:
     * - Message Type (4 bytes)
     * - Timestamp (8 bytes)
     * - Sequence Number (8 bytes)
     * - Source Peer ID (512 bytes)
     * - Destination Peer ID (512 bytes)
     * - Payload Length (4 bytes)
     * - Payload (variable)
     * - Signature (512 bytes)
     *
     * Note: Peer IP and Port are available from UDP packet header.
     * Version is negotiated during handshake, not sent with every message.
     */
    private fun serializeMessage(message: NetworkMessage): ByteArray {
        return mergeByteArrays(
            message.type.ordinal.toByteArray(),
            message.timestamp.toByteArray(),
            message.sequenceNumber.toByteArray(),
            configs.publicKey,
            message.peerNetworkInfo.id, // 512 bytes
            message.payload.size.toByteArray(),
            message.payload,
            message.signature // 512 bytes
        )
    }

    /**
     * Deserializes bytes from network into a NetworkMessage.
     * IP and port are extracted from the UDP packet header, not from message bytes.
     * Returns null if the message is not intended for us (destination peer ID doesn't match our public key).
     */
    private fun deserializeMessage(bytes: ByteArray, senderAddress: String, senderPort: Short): NetworkMessage? {
        var offset = 0

        // Message Type (4 bytes)
        val typeOrdinal = bytes.sliceArray(offset until offset + MESSAGE_TYPE_SIZE).toInt()
        offset += MESSAGE_TYPE_SIZE
        val messageType = NetworkMessageType.values()[typeOrdinal]

        // Timestamp (8 bytes)
        val timestamp = bytes.sliceArray(offset until offset + TIMESTAMP_SIZE).toLong()
        offset += TIMESTAMP_SIZE

        // Sequence Number (8 bytes)
        val sequenceNumber = bytes.sliceArray(offset until offset + SEQUENCE_SIZE).toLong()
        offset += SEQUENCE_SIZE

        // Source Peer ID (512 bytes) - who sent this message
        val sourcePeerId = bytes.sliceArray(offset until offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE

        // Destination Peer ID (512 bytes) - who this message is for
        val destinationPeerId = bytes.sliceArray(offset until offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE

        // Check if this message is intended for us - if not, ignore it
        if (!destinationPeerId.contentEquals(configs.publicKey)) {
            logger.debug(
                "UdpClientTransmitter",
                "Ignoring message from $senderAddress:$senderPort - not intended for us"
            )
            return null
        }

        // Payload Length (4 bytes)
        val payloadLength = bytes.sliceArray(offset until offset + PAYLOAD_SIZE_SIZE).toInt()
        offset += PAYLOAD_SIZE_SIZE

        // Payload (variable)
        val payload = bytes.sliceArray(offset until offset + payloadLength)
        offset += payloadLength

        // Signature (512 bytes)
        val signature = bytes.sliceArray(offset until offset + SIGNATURE_SIZE)

        // Create PeerNetworkInfo for the sender using packet header information
        val peerNetworkInfo = PeerNetworkInfo(sourcePeerId, senderAddress, senderPort, VERSION)

        return NetworkMessage(messageType, payload, peerNetworkInfo, timestamp, sequenceNumber, signature)
    }

    /**
     * Creates a signature for outgoing messages.
     * For now, using a placeholder signature. In production, this should use proper RSA signing.
     */
    private fun createSignature(data: ByteArray): PeerSignature {
        // TODO: Implement proper RSA signature using configs.privateKey
        // For now, create a placeholder signature of the correct size
        return ByteArray(SIGNATURE_SIZE) { 0x00 }
    }
}
