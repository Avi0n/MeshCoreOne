import Foundation
import MC1Services

extension ChatViewModel {
  /// Route a DM enqueue through the service-owned send queue. The
  /// service persists the row and drives the drain. Throws
  /// `.notConnected` if the connection has been torn down between the
  /// optimistic DB write and this call, or `.persistFailed` if the
  /// SwiftData write fails. The caller's catch flips the message to
  /// `.failed` and surfaces the error.
  func enqueueDM(_ envelope: DirectMessageEnvelope) async throws {
    guard let queue = chatSendQueueServiceProvider() else {
      throw ChatSendQueueServiceError.notConnected
    }
    try await queue.enqueueDM(envelope)
  }

  /// Route a channel enqueue through the service-owned send queue.
  /// See `enqueueDM` for the error contract.
  func enqueueChannel(_ envelope: ChannelMessageEnvelope) async throws {
    guard let queue = chatSendQueueServiceProvider() else {
      throw ChatSendQueueServiceError.notConnected
    }
    try await queue.enqueueChannel(envelope)
  }

  /// Signal-only DM enqueue for the manual retry path. The caller has
  /// already persisted the `PendingSend` row via
  /// `PersistenceStore.replacePendingSendForRetry`, so the service must
  /// not double-persist. Throws `.notConnected` if the connection has
  /// been torn down between the persist and this call — the caller's
  /// catch surfaces the failure via `sendErrorMessage`.
  func signalDMEnqueued(_ envelope: DirectMessageEnvelope) async throws {
    guard let queue = chatSendQueueServiceProvider() else {
      throw ChatSendQueueServiceError.notConnected
    }
    await queue.signalDMEnqueued(envelope)
  }
}
