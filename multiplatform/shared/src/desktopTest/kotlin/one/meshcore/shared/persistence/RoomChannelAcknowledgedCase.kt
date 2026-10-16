package one.meshcore.shared.persistence

internal enum class RoomChannelAcknowledgedCase(val firstWriter: String, val expected: RoomChannelWriterCase) {
    SET("saveChannel", RoomChannelWriterCase.SAVE),
    CLEAR_PRESENT("deleteMessagesForChannel", RoomChannelWriterCase.DELETE),
    CLEAR_ORPHAN("deleteMessagesForChannel", RoomChannelWriterCase.MESSAGES),
}
