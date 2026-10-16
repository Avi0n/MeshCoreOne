package one.meshcore.shared.room.ui

/** Placeholder copy while a room-access field is still unloaded. */
internal data class RoomAccessLoadCopy(
    val loading: String,
    val failed: String,
    val dash: String = ROOM_ACCESS_UNLOADED_DASH,
)

internal const val ROOM_ACCESS_UNLOADED_DASH = "—"
