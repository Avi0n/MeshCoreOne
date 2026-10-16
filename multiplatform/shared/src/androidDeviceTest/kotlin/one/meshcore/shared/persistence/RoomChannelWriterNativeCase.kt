package one.meshcore.shared.persistence

internal enum class RoomChannelWriterNativeCase(val method: String) {
    SAVE("saveChannel"),
    BATCH("batchSaveChannels"),
    DELETE("deleteChannel"),
    MESSAGES("deleteMessagesForChannel"),
}
