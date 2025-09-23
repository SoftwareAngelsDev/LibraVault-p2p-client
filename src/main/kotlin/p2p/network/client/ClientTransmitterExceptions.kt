package p2p.network.client

/**
 * Base exception for all client transmitter errors
 */
abstract class ClientTransmitterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when attempting to transmit while the transmitter is not running
 */
class TransmitterNotRunningException(message: String = "Transmitter is not running") : ClientTransmitterException(message)

/**
 * Thrown when the UDP socket is not initialized
 */
class SocketNotInitializedException(message: String = "UDP socket is not initialized") : ClientTransmitterException(message)

/**
 * Thrown when a network transmission fails
 */
class TransmissionFailedException(message: String, cause: Throwable? = null) : ClientTransmitterException(message, cause)

/**
 * Thrown when message serialization fails
 */
class MessageSerializationException(message: String, cause: Throwable? = null) : ClientTransmitterException(message, cause)
