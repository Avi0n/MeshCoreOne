import MC1Services

/// Localized status text lives in the app target because `L10n` is generated
/// here, not in `MC1Services` where `RoomMessageDTO` is declared.
extension RoomMessageDTO {
    var localizedStatusText: String {
        switch status {
        case .pending, .sending:
            return L10n.Chats.Chats.Message.Status.sending
        case .sent:
            return L10n.Chats.Chats.Message.Status.sent
        case .delivered:
            return L10n.Chats.Chats.Message.Status.delivered
        case .failed:
            return L10n.Chats.Chats.Message.Status.failed
        case .retrying:
            return L10n.Chats.Chats.Message.Status.retrying
        }
    }

    var accessibilityStatusLabel: String {
        switch status {
        case .failed:
            return L10n.RemoteNodes.RemoteNodes.Room.Message.Status.failedLabel
        case .pending, .sending, .retrying:
            return L10n.RemoteNodes.RemoteNodes.Room.Message.Status.sendingLabel
        default:
            return L10n.RemoteNodes.RemoteNodes.Room.Message.Status.deliveredLabel
        }
    }
}
