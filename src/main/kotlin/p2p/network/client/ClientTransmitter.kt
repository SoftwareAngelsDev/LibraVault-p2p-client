package p2p.network.client

import p2p.network.PeerNetworkInfo
import p2p.network.client.messages.NetworkMessageType

interface ClientTransmitter {
    /**
     * Sends a message to the specified peer in a non-blocking manner.
     *
     * @throws TransmitterNotRunningException if the transmitter is not started
     * @throws SocketNotInitializedException if the UDP socket is not initialized
     * @throws MessageSerializationException if message serialization fails
     * @throws TransmissionFailedException if network transmission fails
     */
    suspend fun transmit(type: NetworkMessageType, destination: PeerNetworkInfo, payload: ByteArray)

    /**
     * Starts the UDP server to listen for incoming messages
     */
    fun start()

    /**
     * Stops the UDP server and cleans up resources
     */
    fun stop()

    /**
     * Checks if the transmitter is currently running
     */
    fun isRunning(): Boolean
}
