package one.meshcore.shared.persistence

internal enum class RoomContactMetadataWriterCase(val frame: String) {
    UNREAD("one.meshcore.shared.persistence.RoomContactBookkeeping.clearUnreadCount"),
    MENTIONS("one.meshcore.shared.persistence.RoomContactBookkeeping.clearUnreadMentionCount"),
    HEARD("one.meshcore.shared.persistence.RoomContactBookkeeping.touchContactHeard"),
    ;

    fun expected(row: ContactEntity): ContactEntity = when (this) {
        UNREAD -> row.copy(unreadCount = 0)
        MENTIONS -> row.copy(unreadMentionCount = 0)
        HEARD -> row.copy(lastHeardTimestamp = maxOf(row.lastHeardTimestamp ?: 0L, NOW))
    }

    companion object {
        const val NOW = 1_700_000_000L
    }
}
