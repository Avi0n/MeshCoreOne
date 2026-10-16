package one.meshcore.shared.remotenode

/** Optional login knobs for an admin connect. Defaults remember the password and use a direct path. */
data class RepeaterAdminLogin(
    val rememberPassword: Boolean = true,
    val pathLength: Int = 0,
    val onTimeoutKnown: (suspend (Int) -> Unit)? = null,
)
