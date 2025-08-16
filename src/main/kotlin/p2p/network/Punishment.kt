package p2p.network

enum class Punishment(val factor: Double) {
    WRONG_CHALLENGE_RESPONSE(0.5),
}