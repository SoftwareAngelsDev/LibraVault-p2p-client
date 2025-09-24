package p2p.network.client.messages

class CantHandleMessage(val type: NetworkMessageType) : Exception("Cannot handle message type: $type")

class InvalidPayloadException(cause: Exception) : Exception("Invalid payload received", cause)
