package gw

data class GwRequestReponseState(val flags: Int) {
    enum class Flag {
        CLIENT_INTIALIZED,
        REQUEST_SENT,
        RESPONSE_SENT,
        CLIENT_CLOSED,
        SERVER_CLOSED,
        CLIENT_RELEASED_TO_POOL
    }

    fun on(flag: Flag) = GwRequestReponseState(flags or bits(flag))
    fun off(flag: Flag) = GwRequestReponseState(flags and bits(flag).inv())
    operator fun contains(flag: Flag) = flags and bits(flag) > 0

    private fun bits(flag: Flag) = (1 shl flag.ordinal)

    override fun toString(): String {
        return Flag.values().filter { contains(it) }.joinToString(" | ")
    }
}