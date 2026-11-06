import Foundation
import Synchronization

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

  /// Applies a temporary flood scope, sends, and restores the baseline before releasing the slot.
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
    let cancelled = Mutex(false)
    let isCancelled: @Sendable () -> Bool = { cancelled.withLock { $0 } }
    let message = PacketBuilder.sendChannelMessage(channel: channel, text: text, timestamp: timestamp)
    try await withTaskCancellationHandler {
      if let floodScope {
        try await requestResponseSerializer.withSerialization(resumeCallerOnCancel: false) { [self] in
          try await self.sendWithTemporaryFloodScope(
            channel: channel, message: message, floodScope: floodScope,
            expectedSecret: expectedSecret, isCancelled: isCancelled
          )
        }
      } else {
        try await requestResponseSerializer.withSerialization { [self] in
          if let expectedSecret {
            try await validateChannelOccupancy(channel: channel, expectedSecret: expectedSecret, isCancelled: isCancelled)
          }
          let _: Bool = try await sendAndMatchHoldingSerialization(message, beforeSend: {
            if isCancelled() { throw CancellationError() }
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

  /// Keep the slot through restoration so no other command can inherit the override.
  /// An acknowledged `03` stays accepted through cancellation or cleanup failure.
  private func sendWithTemporaryFloodScope(
    channel: UInt8,
    message: Data,
    floodScope: ResolvedFloodScope,
    expectedSecret: Data?,
    isCancelled: @escaping @Sendable () -> Bool
  ) async throws {
    defer { notifyFloodScopeRecoveryIfNeeded() }
    if floodScopeRecoveryRequired { throw CancellationError() }
    if await requestResponseSerializer.responseAttributionIsUncertain() {
      markFloodScopeRecoveryRequired()
      throw MeshCoreError.timeout
    }
    if let expectedSecret {
      do {
        try await validateChannelOccupancy(channel: channel, expectedSecret: expectedSecret, isCancelled: isCancelled)
      } catch {
        if Self.channelWriteFailureRequiresFence(error, isCancelled: isCancelled) {
          markFloodScopeRecoveryRequired()
        }
        throw error
      }
    }
    if isCancelled() { throw CancellationError() }

    let setup = Self.temporaryScopeCommand(floodScope)
    switch await settleChannelCommand(setup.command, isCancelled: isCancelled) {
    case .replied(.accepted):
      break
    case let .replied(.rejected(error)), let .uncertain(.rejected(error)?, _):
      throw error
    case let .notSubmitted(error), let .uncertain(_, error):
      throw error
    }
    if isCancelled() {
      await restoreTemporaryScope(needed: setup.restoresBaseline)
      throw CancellationError()
    }

    switch await settleChannelCommand(message, isCancelled: isCancelled) {
    case let .replied(reply):
      await restoreTemporaryScope(needed: setup.restoresBaseline)
      if case let .rejected(error) = reply { throw error }
    case let .notSubmitted(error):
      await restoreTemporaryScope(needed: setup.restoresBaseline)
      throw error
    case .uncertain(.accepted?, _):
      // The owned OK confirms acceptance, but a reset cannot use the uncertain reply stream.
      return
    case let .uncertain(.rejected(error)?, _):
      throw error
    case let .uncertain(nil, error):
      throw error
    }
  }

  private func restoreTemporaryScope(needed: Bool) async {
    guard needed else { return }
    let reset = await settleChannelCommand(Self.floodScopeBaselineCommand)
    guard case .replied(.accepted) = reset else {
      markFloodScopeRecoveryRequired()
      return
    }
  }

  private static let floodScopeBaselineCommand = PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey())

  private static func temporaryScopeCommand(_ floodScope: ResolvedFloodScope) -> (command: Data, restoresBaseline: Bool) {
    switch floodScope {
    case .unscoped:
      return (PacketBuilder.setFloodScopeUnscoped(), true)
    case let .scope(scope):
      let key = scope.scopeKey()
      return (PacketBuilder.setFloodScope(key), key != FloodScope.disabled.scopeKey())
    }
  }

  private func validateChannelOccupancy(
    channel: UInt8,
    expectedSecret: Data,
    isCancelled: @escaping @Sendable () -> Bool
  ) async throws {
    let _: Bool = try await sendAndMatchHoldingSerialization(PacketBuilder.getChannel(index: channel), beforeSend: {
      if isCancelled() { throw CancellationError() }
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

  private static func channelWriteFailureRequiresFence(
    _ error: Error,
    isCancelled: @Sendable () -> Bool
  ) -> Bool {
    if let failure = error as? MeshCoreError {
      if case .timeout = failure { return true }
      return false
    }
    if error is CancellationError, isCancelled() { return false }
    return true
  }

  /// Retain the first terminal reply even if the write fails, then close the subscription before the next command.
  private func settleChannelCommand(
    _ data: Data,
    isCancelled: @Sendable () -> Bool = { false }
  ) async -> ChannelCommandSettlement {
    let (subscriptionID, events) = await dispatcher.subscribeTracked(filter: Self.isTerminalCommandReply)
    let collector = Task { () -> ChannelCommandSettlement.Reply? in
      for await event in events {
        if let reply = Self.channelCommandReply(event) { return reply }
      }
      return nil
    }

    let uncertainBeforeWrite = await requestResponseSerializer.responseAttributionIsUncertain()
    let writeError: Error?
    do {
      try await sendSessionData(data, beforeSend: {
        if isCancelled() { throw CancellationError() }
      })
      writeError = nil
    } catch {
      writeError = error
    }

    if let writeError {
      let writeAttempted = await requestResponseSerializer.responseAttributionIsUncertain() && !uncertainBeforeWrite
      if writeAttempted {
        // Give the receive loop a chance to publish a buffered reply before closing the subscription.
        await Task.yield()
      }
      await dispatcher.finishSubscription(id: subscriptionID)
      let reply = await collector.value
      if !writeAttempted {
        if !sessionIsRunning { markFloodScopeRecoveryRequired() }
        return .notSubmitted(writeError)
      }
      markFloodScopeRecoveryRequired()
      return .uncertain(reply, writeError)
    }

    let timeout = configuration.defaultTimeout
    await withTaskGroup(of: Void.self) { group in
      group.addTask { _ = await collector.value }
      group.addTask { [clock] in
        try? await clock.sleep(for: .seconds(timeout))
      }
      _ = await group.next()
      // Close the stream before joining the collector so a timeout can release the slot.
      await dispatcher.finishSubscription(id: subscriptionID)
      group.cancelAll()
    }
    if let reply = await collector.value {
      return .replied(reply)
    }
    markFloodScopeRecoveryRequired()
    await requestResponseSerializer.noteUncertainResponseHistory()
    if !sessionIsRunning {
      return .uncertain(nil, CancellationError())
    }
    return .uncertain(nil, MeshCoreError.timeout)
  }

  private static func isTerminalCommandReply(_ event: MeshEvent) -> Bool {
    switch event {
    case .ok, .error: true
    default: false
    }
  }

  private static func channelCommandReply(_ event: MeshEvent) -> ChannelCommandSettlement.Reply? {
    if let error = deviceErrorMatcher(event) { return .rejected(error) }
    if case .ok(nil) = event { return .accepted }
    return nil
  }

  private enum ChannelCommandSettlement {
    enum Reply: Sendable {
      case accepted
      case rejected(MeshCoreError)
    }

    case notSubmitted(Error)
    case replied(Reply)
    case uncertain(Reply?, Error)
  }
}
