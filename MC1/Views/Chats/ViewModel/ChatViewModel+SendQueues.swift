import Foundation
import MC1Services

extension ChatViewModel {
  /// Queues a channel retry; the caller repairs and presents an enqueue failure.
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
