import MC1Services

/// Determines which actions a room message exposes in its actions sheet.
/// Computed once at presentation so the buttons stay stable if the session's
/// permission changes while the sheet is open.
struct RoomMessageActionAvailability {
    let canReply: Bool
    let canSendDM: Bool
    let canSendAgain: Bool

    init(message: RoomMessageDTO, session: RemoteNodeSessionDTO) {
        canReply = !message.isFromSelf && session.canPost
        canSendDM = !message.isFromSelf && message.authorName != nil
        canSendAgain = message.isFromSelf
    }
}
