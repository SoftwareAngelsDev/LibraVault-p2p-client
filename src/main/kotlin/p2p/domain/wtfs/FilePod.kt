package p2p.domain.wtfs

import p2p.utils.convertMBToBytes

typealias PodNumber = Int
typealias UnixTimestamp = Long

class FilePod(
    val number: PodNumber,
    val owner: p2p.domain.RemotePeerId,
    val iNodes: Collection<INode>,
    val updatedAt: UnixTimestamp
) {
    companion object {
        val POD_FIXED_SIZE_BYTES = convertMBToBytes(100).toInt()
    }
}