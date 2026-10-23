import Foundation
import os

public extension MeshCoreSession {
  /// Applies a channel's flood scope and sends while holding one command-response slot.
  func sendChannelMessage(
    channel: UInt8,
    text: String,
    timestamp: Date,
    floodScope: ResolvedFloodScope
  ) async throws {
    let scopeCommand = switch floodScope {
    case .unscoped:
      PacketBuilder.setFloodScopeUnscoped()
    case let .scope(scope):
      PacketBuilder.setFloodScope(scope.scopeKey())
    }
    let messageCommand = PacketBuilder.sendChannelMessage(channel: channel, text: text, timestamp: timestamp)
    let cancelled = OSAllocatedUnfairLock(initialState: false)

    try await withTaskCancellationHandler {
      try await requestResponseSerializer.withSerialization { [self] in
        for command in [scopeCommand, messageCommand] {
          // Cancellation still drains an outstanding ACK, but prevents the next write.
          let _: Bool = try await sendAndMatchHoldingSerialization(command, beforeSend: {
            if cancelled.withLock({ $0 }) { throw CancellationError() }
          }) { event in
            if let error = Self.deviceErrorMatcher(event) {
              return .failure(error)
            }
            if case .ok(nil) = event {
              return .success(true)
            }
            return .ignore
          }
        }
      }
    } onCancel: {
      cancelled.withLock { $0 = true }
    }
  }
}
