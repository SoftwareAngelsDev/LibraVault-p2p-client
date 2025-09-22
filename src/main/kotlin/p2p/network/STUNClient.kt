@file:OptIn(ExperimentalAtomicApi::class)

package p2p.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import p2p.helpers.ConfigurationManager
import p2p.utils.Logger
import java.net.*
import java.nio.ByteBuffer
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random

open class STUNClient {
    companion object {
        private const val TAG = "STUNClient"
        private const val STUN_REQUEST_TYPE = 0x0001
        private const val MAGIC_COOKIE = 0x2112A442
        private const val BINDING_RESPONSE_SUCCESS = 0x0101
        private const val MAPPED_ADDRESS_ATTRIBUTE = 0x0001
        private const val XOR_MAPPED_ADDRESS_ATTRIBUTE = 0x0020
        private const val STUN_TIMEOUT_MS = 5000 // 5 seconds timeout
        private const val DEFAULT_STUN_PORT = 3478
        private const val DEFAULT_GOOGLE_STUN_PORT = 19302
    }

    private val logger = Logger()
    private val stunServers = ConfigurationManager.DEFAULT_STUN_SERVERS

    /**
     * Protocol used for network communication
     */
    enum class NetworkProtocol {
        TCP,
        UDP
    }

    /**
     * Information about network addresses
     *
     * @param publicAddress The public IP address as seen from the internet
     * @param publicPort The public port as seen from the internet
     * @param localAddress The local IP address of the machine
     * @param localPort The local port used for the connection
     * @param protocol The protocol (TCP/UDP) used for the connection
     */
    data class AddressInfo(
        val publicAddress: String,
        val publicPort: Int,
        val localAddress: String,
        val localPort: Int,
        val protocol: NetworkProtocol = NetworkProtocol.TCP
    )

    data class NetworkInterfaceInfo(
        val name: String,
        val displayName: String,
        val addresses: List<String>,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isVirtual: Boolean
    )

    /**
     * Get the public IP address and port using STUN protocol
     * Tries UDP first, then falls back to TCP if UDP fails
     *
     * @param timeoutMs Timeout in milliseconds for STUN server connections
     * @param preferUdp Whether to try UDP first (true) or TCP first (false)
     * @return AddressInfo containing public and local address information
     */
    suspend fun findMyPublicAddress(
        timeoutMs: Int = STUN_TIMEOUT_MS,
        preferUdp: Boolean = true
    ): AddressInfo = withContext(Dispatchers.IO) {
        // Parse all STUN servers
        val parsedServers = stunServers.mapNotNull { stunServer ->
            try {
                // Parse STUN URI format (stun:hostname:port)
                val parts = stunServer.split(":")
                if (parts.size < 3 || parts[0] != "stun") {
                    logger.error(TAG, "Invalid STUN server URI format: $stunServer")
                    return@mapNotNull null
                }

                val host = parts[1]
                val port = parts[2].toIntOrNull() ?: DEFAULT_STUN_PORT

                // Google servers typically use port 19302
                val isGoogleServer = host.contains("google.com")
                val effectivePort = if (isGoogleServer && port == 3478) DEFAULT_GOOGLE_STUN_PORT else port

                Triple(stunServer, host, effectivePort)
            } catch (e: Exception) {
                logger.error(TAG, "Error parsing STUN server URI: $stunServer", e)
                null
            }
        }

        // First try the preferred protocol
        val primaryProtocol = if (preferUdp) NetworkProtocol.UDP else NetworkProtocol.TCP
        val secondaryProtocol = if (preferUdp) NetworkProtocol.TCP else NetworkProtocol.UDP

        // Try with primary protocol first
        logger.info(TAG, "Trying STUN servers with $primaryProtocol first")
        val primaryResult = tryStunWithProtocol(parsedServers, primaryProtocol, timeoutMs)
        if (primaryResult != null) {
            // Verify the public IP with a trusted HTTP service
            val verifiedIp = verifyPublicIpWithHttp()
            if (verifiedIp != null && verifiedIp != primaryResult.publicAddress) {
                logger.warn(
                    TAG,
                    "STUN server reported IP ${primaryResult.publicAddress} but HTTP service reported $verifiedIp"
                )
                // Return the verified IP instead, but keep the STUN-discovered port
                return@withContext primaryResult.copy(publicAddress = verifiedIp)
            }
            return@withContext primaryResult
        }

        // Fall back to secondary protocol
        logger.info(TAG, "$primaryProtocol STUN failed, trying with $secondaryProtocol")
        val secondaryResult = tryStunWithProtocol(parsedServers, secondaryProtocol, timeoutMs)
        if (secondaryResult != null) {
            // Verify the public IP with a trusted HTTP service
            val verifiedIp = verifyPublicIpWithHttp()
            if (verifiedIp != null && verifiedIp != secondaryResult.publicAddress) {
                logger.warn(
                    TAG,
                    "STUN server reported IP ${secondaryResult.publicAddress} but HTTP service reported $verifiedIp"
                )
                // Return the verified IP instead, but keep the STUN-discovered port
                return@withContext secondaryResult.copy(publicAddress = verifiedIp)
            }
            return@withContext secondaryResult
        }

        // If we get here, all STUN servers failed with both protocols
        throw Exception("Failed to determine public address: All STUN servers failed with both UDP and TCP")
    }

    /**
     * Try STUN servers with a specific protocol
     */
    protected open suspend fun tryStunWithProtocol(
        parsedServers: List<Triple<String, String, Int>>,
        protocol: NetworkProtocol,
        timeoutMs: Int
    ): AddressInfo? = withContext(Dispatchers.IO) {
        for ((serverUri, host, port) in parsedServers) {
            logger.debug(TAG, "Trying STUN server over $protocol: $serverUri")

            try {
                val serverAddress = InetAddress.getByName(host)
                logger.debug(TAG, "Resolved STUN server address: ${serverAddress.hostAddress}:$port")

                val result = when (protocol) {
                    NetworkProtocol.UDP -> stunOverUdp(serverAddress, port, timeoutMs)
                    NetworkProtocol.TCP -> stunOverTcp(serverAddress, port, timeoutMs)
                }

                if (result != null) {
                    logger.info(TAG, "Successfully obtained address from $protocol STUN server $serverUri")
                    return@withContext result
                }
            } catch (e: Exception) {
                logger.error(TAG, "Error with $protocol STUN server $serverUri: ${e.message}", e)
                // Continue to the next server
            }
        }

        return@withContext null
    }


    /**
     * Perform a STUN binding request over UDP to get the public IP and port
     */
    protected open suspend fun stunOverUdp(
        serverAddress: InetAddress,
        serverPort: Int,
        timeoutMs: Int
    ): AddressInfo? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            // Create UDP socket
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs

            logger.debug(TAG, "Created UDP socket on local port ${socket.localPort}")

            // Create STUN request
            val transactionId = ByteArray(12)
            Random.nextBytes(transactionId)

            val requestData = createStunBindingRequest(transactionId)

            // Send request over UDP
            val requestPacket = DatagramPacket(requestData, requestData.size, serverAddress, serverPort)
            socket.send(requestPacket)

            logger.debug(TAG, "Sent STUN binding request over UDP, waiting for response...")

            // Receive response
            val responseBuffer = ByteArray(512)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            logger.debug(TAG, "Received STUN response over UDP: ${responsePacket.length} bytes")

            // Process response
            val publicAddress = processStunResponse(responseBuffer, responsePacket.length, transactionId)

            // Get the local address
            val localAddress = socket.localAddress.hostAddress
            val localPort = socket.localPort

            if (publicAddress != null) {
                val (publicIp, publicPort) = publicAddress
                logger.debug(TAG, "Found public address via UDP STUN: $publicIp:$publicPort")
                logger.debug(TAG, "Local address: $localAddress:$localPort")

                return@withContext AddressInfo(
                    publicAddress = publicIp,
                    publicPort = publicPort,
                    localAddress = localAddress,
                    localPort = localPort,
                    protocol = NetworkProtocol.UDP
                )
            }

            logger.debug(TAG, "Could not parse a valid address from UDP STUN response")
            return@withContext null
        } catch (e: Exception) {
            logger.error(TAG, "Error in UDP STUN request: ${e.message}", e)
            return@withContext null
        } finally {
            socket?.close()
        }
    }

    /**
     * Perform a STUN binding request over TCP to get the public IP and port
     */
    protected open suspend fun verifyPublicIpWithHttp(): String? = withContext(Dispatchers.IO) {
        // List of trusted services that return just the IP address as plain text
        val ipServices = listOf(
            "https://api.ipify.org",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com",
            "https://ifconfig.me/ip"
        )

        for (service in ipServices) {
            try {
                logger.debug(TAG, "Verifying public IP with service: $service")

                val url = URL(service)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "LibraVault-P2P-Client")

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    // Clean up and validate the IP address
                    val trimmed = response.trim()

                    // Simple validation that it looks like an IP address
                    if (trimmed.matches("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""".toRegex())) {
                        logger.info(TAG, "Verified public IP using $service: $trimmed")
                        return@withContext trimmed
                    }
                }
            } catch (e: Exception) {
                logger.error(TAG, "Failed to verify IP with service $service: ${e.message}")
                // Continue to next service
            }
        }

        logger.warn(TAG, "Could not verify public IP with any HTTP service")
        return@withContext null
    }

    protected open suspend fun stunOverTcp(
        serverAddress: InetAddress,
        serverPort: Int,
        timeoutMs: Int
    ): AddressInfo? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            // Create TCP socket and connect to STUN server
            socket = Socket()
            socket.soTimeout = timeoutMs
            try {
                socket.connect(InetSocketAddress(serverAddress, serverPort), timeoutMs)
            } catch (e: SocketTimeoutException) {
                throw Exception(
                    "Failed to connect to STUN server over TCP: ${serverAddress.hostAddress}:$serverPort",
                    e
                )
            }

            logger.debug(TAG, "Connected to STUN server over TCP: ${serverAddress.hostAddress}:$serverPort")

            // Create STUN request
            val transactionId = ByteArray(12)
            Random.nextBytes(transactionId)

            val requestData = createStunBindingRequest(transactionId)

            // Send request over TCP
            val outputStream = socket.getOutputStream()
            outputStream.write(requestData)
            outputStream.flush()

            logger.debug(TAG, "Sent STUN binding request over TCP, waiting for response...")

            // Receive response
            val inputStream = socket.getInputStream()
            val responseBuffer = ByteArray(512)
            val bytesRead = inputStream.read(responseBuffer)

            if (bytesRead <= 0) {
                logger.error(TAG, "Received empty response from STUN server")
                return@withContext null
            }

            logger.debug(TAG, "Received STUN response over TCP: $bytesRead bytes")

            // Process response
            val publicAddress = processStunResponse(responseBuffer, bytesRead, transactionId)

            // Get the local address
            val localAddress = socket.localAddress.hostAddress
            val localPort = socket.localPort

            if (publicAddress != null) {
                val (publicIp, publicPort) = publicAddress
                logger.debug(TAG, "Found public address via TCP STUN: $publicIp:$publicPort")
                logger.debug(TAG, "Local address: $localAddress:$localPort")

                return@withContext AddressInfo(
                    publicAddress = publicIp,
                    publicPort = publicPort,
                    localAddress = localAddress,
                    localPort = localPort,
                    protocol = NetworkProtocol.TCP
                )
            }

            logger.debug(TAG, "Could not parse a valid address from TCP STUN response")
            return@withContext null
        } catch (e: Exception) {
            logger.error(TAG, "Error in TCP STUN request: ${e.message}", e)
            return@withContext null
        } finally {
            socket?.close()
        }
    }

    private fun createStunBindingRequest(transactionId: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(20) // STUN header is 20 bytes

        // Message Type: Binding Request (0x0001)
        buffer.putShort(STUN_REQUEST_TYPE.toShort())

        // Message Length: 0 bytes (no attributes)
        buffer.putShort(0)

        // Magic Cookie
        buffer.putInt(MAGIC_COOKIE)

        // Transaction ID (12 bytes)
        buffer.put(transactionId)

        return buffer.array()
    }

    private fun processStunResponse(
        response: ByteArray,
        length: Int,
        expectedTransactionId: ByteArray
    ): Pair<String, Int>? {
        val buffer = ByteBuffer.wrap(response, 0, length)

        // Check message type (binding response)
        val messageType = buffer.short.toInt() and 0xFFFF
        if (messageType != BINDING_RESPONSE_SUCCESS) {
            logger.error(TAG, "Not a binding response. Type: ${messageType.toString(16)}", null)
            return null
        }

        // Check message length
        val messageLength = buffer.short.toInt() and 0xFFFF
        if (messageLength < 4) { // At least one attribute with a value
            logger.error(TAG, "Message too short: $messageLength", null)
            return null
        }

        // Check magic cookie
        val magicCookie = buffer.int
        if (magicCookie != MAGIC_COOKIE) {
            logger.error(TAG, "Invalid magic cookie: ${magicCookie.toString(16)}", null)
            return null
        }

        // Verify transaction ID
        val transactionId = ByteArray(12)
        buffer.get(transactionId)
        if (!transactionId.contentEquals(expectedTransactionId)) {
            logger.error(TAG, "Transaction ID mismatch", null)
            return null
        }

        // Process attributes
        var ipAddress: String? = null
        var port: Int = -1

        var attributesProcessed = 0
        while (attributesProcessed < messageLength) {
            val attributeType = buffer.short.toInt() and 0xFFFF
            val attributeLength = buffer.short.toInt() and 0xFFFF

            when (attributeType) {
                XOR_MAPPED_ADDRESS_ATTRIBUTE -> {
                    // Skip attribute header (family and reserved)
                    buffer.position(buffer.position() + 1)

                    // XOR port with most significant 16 bits of magic cookie
                    val xorPort = buffer.short.toInt() and 0xFFFF
                    port = xorPort xor (MAGIC_COOKIE shr 16)

                    // XOR address with magic cookie
                    val xorAddress = buffer.int
                    val address = xorAddress xor MAGIC_COOKIE

                    ipAddress = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(address).array()).hostAddress

                    // Found what we need, can break
                    break
                }

                MAPPED_ADDRESS_ATTRIBUTE -> {
                    // Only use if XOR-MAPPED-ADDRESS is not found
                    if (ipAddress != null) continue

                    // Skip attribute header (family)
                    buffer.position(buffer.position() + 1)

                    // Get port directly
                    port = buffer.short.toInt() and 0xFFFF

                    // Get address directly
                    val addressBytes = ByteArray(4)
                    buffer.get(addressBytes)
                    ipAddress = InetAddress.getByAddress(addressBytes).hostAddress
                }

                else -> {
                    // Skip unknown attribute
                    buffer.position(buffer.position() + attributeLength)
                }
            }

            // Pad to 4-byte boundary if needed
            if (attributeLength % 4 != 0) {
                buffer.position(buffer.position() + (4 - attributeLength % 4))
            }

            attributesProcessed += attributeLength + 4 // 4 for attribute header
        }

        return if (ipAddress != null && port != -1) {
            Pair(ipAddress, port)
        } else {
            null
        }
    }

    /**
     * Gets all local network interfaces and their IP addresses.
     *
     * @param includeLoopback Whether to include loopback interfaces (127.0.0.1, ::1)
     * @param includeVirtual Whether to include virtual interfaces
     * @param onlyIPv4 Whether to only include IPv4 addresses
     * @return List of NetworkInterfaceInfo objects containing interface details and addresses
     */
    fun getAllLocalAddresses(
        includeLoopback: Boolean = false,
        includeVirtual: Boolean = false,
        onlyIPv4: Boolean = true
    ): List<NetworkInterfaceInfo> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { iface ->
                val isValidIface = iface.isUp &&
                        (includeLoopback || !iface.isLoopback) &&
                        (includeVirtual || !iface.isVirtual)
                isValidIface
            }
            .map { iface ->
                val addresses = iface.inetAddresses.asSequence()
                    .filter { addr -> !onlyIPv4 || addr is java.net.Inet4Address }
                    .map { it.hostAddress }
                    .toList()

                NetworkInterfaceInfo(
                    name = iface.name,
                    displayName = iface.displayName,
                    addresses = addresses,
                    isUp = iface.isUp,
                    isLoopback = iface.isLoopback,
                    isVirtual = iface.isVirtual
                )
            }
            .filter { it.addresses.isNotEmpty() }
            .toList()
    }.getOrElse { ex ->
        logger.error(TAG, "Error getting network interfaces: ${ex.message}", ex)
        emptyList()
    }

    /**
     * Gets all local IP addresses from all network interfaces as a simple list.
     *
     * @param includeLoopback Whether to include loopback addresses (127.0.0.1, ::1)
     * @param includeVirtual Whether to include virtual interface addresses
     * @param onlyIPv4 Whether to only include IPv4 addresses
     * @return List of all IP addresses as strings
     */
    fun getAllLocalIPs(
        includeLoopback: Boolean = false,
        includeVirtual: Boolean = false,
        onlyIPv4: Boolean = true
    ): List<String> {
        return getAllLocalAddresses(includeLoopback, includeVirtual, onlyIPv4)
            .flatMap { it.addresses }
    }
}