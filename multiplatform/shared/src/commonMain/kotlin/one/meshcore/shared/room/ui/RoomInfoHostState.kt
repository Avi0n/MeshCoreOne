package one.meshcore.shared.room.ui

/**
 * Which Room Info destinations are up. Telemetry and settings stack on Info so dismissing them returns
 * here, matching Channel Info's nested overlays.
 */
internal data class RoomInfoHostState(
    val showInfo: Boolean = false,
    val showStatus: Boolean = false,
    val showSettings: Boolean = false,
) {
    fun openInfo(): RoomInfoHostState = copy(showInfo = true)

    /** Done on Info also drops any child that was stacked on it. */
    fun dismissInfo(): RoomInfoHostState = RoomInfoHostState()

    fun openStatus(): RoomInfoHostState = copy(showStatus = true)

    fun dismissStatus(): RoomInfoHostState = copy(showStatus = false)

    fun openSettings(): RoomInfoHostState = copy(showSettings = true)

    fun dismissSettings(): RoomInfoHostState = copy(showSettings = false)
}
