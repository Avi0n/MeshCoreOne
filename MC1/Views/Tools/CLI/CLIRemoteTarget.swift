import MC1Services

/// Which node's CLI a remote completion list is for.
enum CLIRemoteTarget: Hashable, Sendable {
  case repeater
  case room
  /// A chat contact's radio. Callers set this directly. `init?(contactType:)` stays nil for chat.
  case radio

  init(role: RemoteNodeRole) {
    switch role {
    case .repeater: self = .repeater
    case .roomServer: self = .room
    }
  }

  init?(contactType: ContactType) {
    switch contactType {
    case .repeater: self = .repeater
    case .room: self = .room
    case .chat: return nil
    }
  }
}
