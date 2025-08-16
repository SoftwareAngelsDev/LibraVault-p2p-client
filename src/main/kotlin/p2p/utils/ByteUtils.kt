package p2p.utils

fun convertKBToBytes(kilobytes: Long): Long {
    return kilobytes * 1024
}

fun convertMBToBytes(megabytes: Long): Long {
    return megabytes * 1024 * 1024
}

fun convertGBToBytes(gigabytes: Long): Long {
    return convertMBToBytes(gigabytes * 1024)
}