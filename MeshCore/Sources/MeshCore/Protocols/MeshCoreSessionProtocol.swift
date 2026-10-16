import Foundation

/// Defines the interface for MeshCore device communication.
///
/// This protocol abstracts the core mesh communication operations used by consuming
/// service layers, allowing them to be tested without a real device connection. It is
/// a composition of the session role protocols in this directory; consumers that touch
/// only one or two session capabilities should declare those roles directly (for
/// example `any ChannelSessionOps`) so their signatures reveal what they use, while
/// broad consumers and conformers keep this umbrella.
///
/// ## Usage
///
/// Services can accept this protocol type for dependency injection:
/// ```swift
/// actor MyService {
///     private let session: any MeshCoreSessionProtocol
///
///     init(session: any MeshCoreSessionProtocol) {
///         self.session = session
///     }
/// }
/// ```
public protocol MeshCoreSessionProtocol:
  SessionEventStreaming,
  MessagingSessionOps,
  ContactSessionOps,
  ChannelSessionOps,
  MessageFetchSessionOps {}
