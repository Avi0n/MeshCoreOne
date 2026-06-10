/// User actions dispatched from a message's context menu, routed to handlers
/// by `ChatConversationView.dispatch(_:for:)`.
enum MessageAction: Equatable {
    case react(String)
    case moreEmojis
    case reply
    case copy
    case sendAgain
    case sendDM
    case details
    case blockSender
    case delete
}
