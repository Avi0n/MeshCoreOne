import Foundation

/// Protocol for PersistenceStore to enable testability of dependent services.
///
/// This protocol abstracts the SwiftData persistence operations used by services,
/// allowing them to be tested with mock implementations. It is a composition of
/// the role protocols under `Protocols/Persistence/`; consumers that touch only
/// one or two store capabilities should declare those roles directly (for
/// example `any MessagePersisting & ContactPersisting`) so their signatures
/// reveal what they use, while broad consumers and conformers keep this
/// umbrella.
///
/// ## Usage
///
/// Services can accept this protocol type for dependency injection:
/// ```swift
/// actor MyService {
///     private let dataStore: any PersistenceStoreProtocol
///
///     init(dataStore: any PersistenceStoreProtocol) {
///         self.dataStore = dataStore
///     }
/// }
/// ```
public protocol PersistenceStoreProtocol:
  MessagePersisting,
  DevicePersisting,
  ContactPersisting,
  ChannelPersisting,
  TracePathPersisting,
  HeardRepeatPersisting,
  DebugLogPersisting,
  LinkPreviewPersisting,
  RxLogPersisting,
  RoomPersisting,
  DiscoveredNodePersisting,
  ReactionPersisting,
  NodeSnapshotPersisting {
  func captureReceiveScope(radioID: UUID, link: ReceiveScope.Lifetime) async throws -> ReceiveScope?
  func admitDirectMessage(
    _ message: MessageDTO,
    wireTextType: UInt8,
    contact: ContactDTO?,
    scope: ReceiveScope,
    preference: TextRulesPreference?
  ) async throws -> DirectMessageAdmission
  func currentDirectMessageContact(_ contact: ContactDTO, scope: ReceiveScope) async throws -> ContactDTO?
  func materializeContactForDirectMessage(
    from frame: ContactFrame,
    receivedAt: Date,
    scope: ReceiveScope
  ) async throws -> ContactDTO?
  func touchDirectMessageContact(_ contact: ContactDTO, scope: ReceiveScope, at date: Date) async throws
  func updateContactAfterDirectMessage(
    _ message: MessageDTO,
    contact: ContactDTO,
    scope: ReceiveScope,
    isViewingContact: Bool
  ) async throws -> ContactDTO?
  func admitDirectMessageReaction(
    _ reaction: ReactionDTO,
    contact: ContactDTO,
    scope: ReceiveScope
  ) async throws -> ReactionAdmission?
  func shouldSuppressChannelMessage(
    _ message: MessageDTO,
    frame: ChannelMessage,
    channel: ChannelDTO,
    scope: ReceiveScope?,
    preference: TextRulesPreference?
  ) async throws -> Bool
}

public extension PersistenceStoreProtocol {
  func captureReceiveScope(radioID: UUID, link: ReceiveScope.Lifetime) async throws -> ReceiveScope? {
    guard link.isCurrent else { return nil }
    return ReceiveScope(radioID: radioID, link: link, partition: .init())
  }

  func admitDirectMessage(
    _ message: MessageDTO,
    wireTextType: UInt8,
    contact: ContactDTO?,
    scope: ReceiveScope,
    preference: TextRulesPreference?
  ) async throws -> DirectMessageAdmission {
    .discarded
  }

  func currentDirectMessageContact(_ contact: ContactDTO, scope: ReceiveScope) async throws -> ContactDTO? {
    nil
  }

  func materializeContactForDirectMessage(
    from frame: ContactFrame,
    receivedAt: Date,
    scope: ReceiveScope
  ) async throws -> ContactDTO? {
    nil
  }

  func touchDirectMessageContact(_ contact: ContactDTO, scope: ReceiveScope, at date: Date) async throws {}

  func updateContactAfterDirectMessage(
    _ message: MessageDTO,
    contact: ContactDTO,
    scope: ReceiveScope,
    isViewingContact: Bool
  ) async throws -> ContactDTO? {
    nil
  }

  func admitDirectMessageReaction(
    _ reaction: ReactionDTO,
    contact: ContactDTO,
    scope: ReceiveScope
  ) async throws -> ReactionAdmission? {
    nil
  }

  func shouldSuppressChannelMessage(
    _ message: MessageDTO,
    frame: ChannelMessage,
    channel: ChannelDTO,
    scope: ReceiveScope?,
    preference: TextRulesPreference?
  ) async throws -> Bool {
    false
  }
}
