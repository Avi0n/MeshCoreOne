/// User actions dispatched from the message actions sheet, routed to handlers
/// by `ChatConversationView.dispatch(_:for:)`.
enum MessageAction: Equatable {
  case react(String)
  case reply
  case copy
  case sendAgain
  case sendDM
  case blockSender
  case delete
}
