package p2p.domain

import p2p.domain.wtfs.PodNumber

class PodDistributedHashTable(
    val knownPods: Collection<RemotePeerId>
) {
    class KnownPod(
        val owner: p2p.domain.RemotePeerId,
        val number: PodNumber,
        val peers: Collection<RemotePeerId>
    )
}