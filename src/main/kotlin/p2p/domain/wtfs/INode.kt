package p2p.domain.wtfs

import p2p.domain.AreYouStupidException

typealias FileRepositoryPath = String
typealias FileId = FileRepositoryPath
typealias FileContentLengthBytes = Int
typealias FileContentHash = Int
typealias FileContent = ByteArray
typealias FileReservedMetadata = Byte
typealias FilePublicStatus = Boolean

class INode(
    val path: FileRepositoryPath,
    val isPublic: FilePublicStatus = false,
    val reserved: FileReservedMetadata, // first bit ignored, i.e. masked as 0x7F
    val createdAt: UnixTimestamp = System.currentTimeMillis(),
    val updatedAt: UnixTimestamp = System.currentTimeMillis(),
    val contentLength: FileContentLengthBytes,
    val contentHash: FileContentHash,
) {
    val fileId = path

    init {
        if (path.length > 10_000) {
            throw AreYouStupidException("What are you really trying to do? Why such a long path with ${path.length} characters?")
        } else if (path.isEmpty()) {
            throw AreYouStupidException("Path cannot be empty")
        }
    }
}
