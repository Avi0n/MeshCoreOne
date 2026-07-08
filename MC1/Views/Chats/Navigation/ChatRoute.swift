import Foundation
import MC1Services

enum ChatRoute: Hashable {
  case direct(ContactDTO)
  case channel(ChannelDTO)
  case room(RemoteNodeSessionDTO)

  enum Kind: UInt8, Hashable {
    case direct
    case channel
    case room
  }

  var kind: Kind {
    switch self {
    case .direct:
      .direct
    case .channel:
      .channel
    case .room:
      .room
    }
  }

  var conversationID: UUID {
    switch self {
    case let .direct(contact):
      contact.id
    case let .channel(channel):
      channel.id
    case let .room(session):
      session.id
    }
  }

  static func == (lhs: ChatRoute, rhs: ChatRoute) -> Bool {
    lhs.kind == rhs.kind && lhs.conversationID == rhs.conversationID
  }

  func hash(into hasher: inout Hasher) {
    hasher.combine(kind)
    hasher.combine(conversationID)
  }

  init(conversation: Conversation) {
    switch conversation {
    case let .direct(contact):
      self = .direct(contact)
    case let .channel(channel):
      self = .channel(channel)
    case let .room(session):
      self = .room(session)
    }
  }

  /// The conversation payload to prefetch for this route, or `nil` for rooms,
  /// which use a separate view with its own load path.
  var chatConversationType: ChatConversationType? {
    switch self {
    case let .direct(contact):
      .dm(contact)
    case let .channel(channel):
      .channel(channel)
    case .room:
      nil
    }
  }

  var roomIsConnected: Bool? {
    guard case let .room(session) = self else { return nil }
    return session.isConnected
  }

  func toConversation() -> Conversation {
    switch self {
    case let .direct(contact):
      .direct(contact)
    case let .channel(channel):
      .channel(channel)
    case let .room(session):
      .room(session)
    }
  }

  func refreshedPayload(from conversations: [Conversation]) -> ChatRoute? {
    guard let match = conversations.first(where: { conversation in
      let route = ChatRoute(conversation: conversation)
      return route.kind == kind && route.conversationID == conversationID
    }) else {
      return nil
    }

    return ChatRoute(conversation: match)
  }
}
