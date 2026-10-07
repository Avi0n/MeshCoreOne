import Foundation

/// One-shot scroll-to-message intent, bound to the conversation that should
/// consume it so a later open cannot steal another conversation's target.
struct PendingScrollTarget: Equatable, Sendable {
  let kind: ChatRoute.Kind
  let conversationID: UUID
  let messageID: UUID
  let requestID: UUID

  init(route: ChatRoute, messageID: UUID, requestID: UUID = UUID()) {
    kind = route.kind
    conversationID = route.conversationID
    self.messageID = messageID
    self.requestID = requestID
  }

  func matches(kind: ChatRoute.Kind, conversationID: UUID) -> Bool {
    self.kind == kind && self.conversationID == conversationID
  }
}
