package p2p.utils

import java.util.*

fun ByteArray.prettyPrint(): String {
    return Base64.getEncoder().encodeToString(this).let {
        val firstChars = it.take(5)
        val lastChars = it.takeLast(5)
        return "$firstChars...$lastChars"
    }
}