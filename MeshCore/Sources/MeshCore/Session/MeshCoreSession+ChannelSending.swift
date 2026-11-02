import Foundation
import os

public extension MeshCoreSession {
  /// Sends a message to a channel.
  ///
  /// Channel messages are broadcast to all nodes with the same channel configuration.
  ///
  /// - Parameters:
  ///   - channel: Channel index (0-255).
  ///   - text: The message text to send.
  ///   - timestamp: Message timestamp. Defaults to current time.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func sendChannelMessage(
    channel: UInt8,
    text: String,
    timestamp: Date = Date()
  ) async throws {
    try await performChannelSend(channel: channel, text: text, timestamp: timestamp, floodScope: nil, expectedSecret: nil)
  }

  /// Applies a channel's flood scope and sends while holding one command-response slot.
  func sendChannelMessage(
    channel: UInt8,
    text: String,
    timestamp: Date,
    floodScope: ResolvedFloodScope
  ) async throws {
    try await performChannelSend(channel: channel, text: text, timestamp: timestamp, floodScope: floodScope, expectedSecret: nil)
  }

  /// Verifies channel occupancy and sends without allowing a channel replacement between them.
  func sendVerifiedChannelMessage(
    channel: UInt8,
    text: String,
    timestamp: Date,
    floodScope: ResolvedFloodScope?,
    expectedSecret: Data
  ) async throws {
    try await performChannelSend(channel: channel, text: text, timestamp: timestamp, floodScope: floodScope, expectedSecret: expectedSecret)
  }

  private static let unconfiguredChannelSecretByte: UInt8 = 0

  private static let channelOccupancyMismatch = MeshCoreError.invalidResponse(
    expected: "unchanged channel occupancy", got: "different or missing channel occupancy"
  )

  private func performChannelSend(
    channel: UInt8,
    text: String,
    timestamp: Date,
    floodScope: ResolvedFloodScope?,
    expectedSecret: Data?
  ) async throws {
    var commands: [Data] = []
    if let floodScope {
      let scopeCommand = switch floodScope {
      case .unscoped:
        PacketBuilder.setFloodScopeUnscoped()
      case let .scope(scope):
        PacketBuilder.setFloodScope(scope.scopeKey())
      }
      commands.append(scopeCommand)
    }
    commands.append(PacketBuilder.sendChannelMessage(channel: channel, text: text, timestamp: timestamp))
    let cancelled = OSAllocatedUnfairLock(initialState: false)

    try await withTaskCancellationHandler {
      try await requestResponseSerializer.withSerialization { [self, commands] in
        if let expectedSecret {
          let _: Bool = try await sendAndMatchHoldingSerialization(PacketBuilder.getChannel(index: channel), beforeSend: {
            if cancelled.withLock({ $0 }) { throw CancellationError() }
          }) { event in
            if case let .error(code) = event, code == ErrorCode.notFound.rawValue {
              return .failure(Self.channelOccupancyMismatch)
            }
            if let error = Self.deviceErrorMatcher(event) { return .failure(error) }
            guard case let .channelInfo(info) = event, info.index == channel else { return .ignore }
            guard info.secret == expectedSecret,
                  !info.name.isEmpty || info.secret.contains(where: { $0 != Self.unconfiguredChannelSecretByte }) else {
              return .failure(Self.channelOccupancyMismatch)
            }
            return .success(true)
          }
        }
        for command in commands {
          // Cancellation still drains the current response, but prevents the next write.
          let _: Bool = try await sendAndMatchHoldingSerialization(command, beforeSend: {
            if cancelled.withLock({ $0 }) { throw CancellationError() }
          }) { event in
            if let error = Self.deviceErrorMatcher(event) { return .failure(error) }
            if case .ok(nil) = event { return .success(true) }
            return .ignore
          }
        }
      }
    } onCancel: {
      cancelled.withLock { $0 = true }
    }
  }
}
