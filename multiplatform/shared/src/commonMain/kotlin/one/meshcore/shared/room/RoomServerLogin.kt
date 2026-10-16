package one.meshcore.shared.room

/** Optional login knobs for joining a room. Defaults remember the password and use a direct path. */
data class RoomServerLogin(
    val rememberPassword: Boolean = true,
    val pathLength: Int = 0,
    val onTimeoutKnown: (suspend (Int) -> Unit)? = null,
)
