import Foundation
#if canImport(Synchronization)
  import Synchronization
#endif

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
    try await performChannelSend(channel: channel, text: text, timestamp: timestamp, floodScope: nil)
  }

  /// Writes the flood-scope command, then the channel message, then clears a non-baseline scope before releasing the slot.
  func sendChannelMessage(
    channel: UInt8,
    text: String,
    timestamp: Date,
    floodScope: ResolvedFloodScope
  ) async throws {
    let message = PacketBuilder.sendChannelMessage(channel: channel, text: text, timestamp: timestamp)
    try await performChannelCommand(message, floodScope: floodScope)
  }

  /// Applies a channel's flood scope and sends binary GROUP_DATA without interleaving another command.
  func sendChannelData(
    channelIndex: UInt8,
    dataType: UInt16,
    payload: Data,
    floodScope: ResolvedFloodScope
  ) async throws {
    let message = try PacketBuilder.sendChannelData(
      channelIndex: channelIndex, dataType: dataType, payload: payload
    )
    try await performChannelCommand(message, floodScope: floodScope)
  }

  /// Reads the channel and sends while holding one command slot, so another command cannot land between them.
  func sendVerifiedChannelMessage(
    channel: UInt8,
    text: String,
    timestamp: Date,
    floodScope: ResolvedFloodScope?,
    expectedSecret: Data
  ) async throws {
    let cancelled = Mutex(false)
    let isCancelled: @Sendable () -> Bool = { cancelled.withLock { $0 } }
    let message = PacketBuilder.sendChannelMessage(channel: channel, text: text, timestamp: timestamp)
    try await withTaskCancellationHandler {
      try await requestResponseSerializer.withSerialization(resumeCallerOnCancel: false) { [self] in
        let _: Bool = try await sendAndMatchHoldingSerialization(
          PacketBuilder.getChannel(index: channel),
          beforeSend: {
            if isCancelled() { throw CancellationError() }
          }
        ) { event in
          if case let .error(code) = event, code == ErrorCode.notFound.rawValue {
            return .failure(Self.channelOccupancyMismatch)
          }
          if let error = Self.deviceErrorMatcher(event) { return .failure(error) }
          guard case let .channelInfo(info) = event, info.index == channel else { return .ignore }
          guard info.secret == expectedSecret,
                !info.name.isEmpty || info.secret.contains(where: { $0 != 0 }) else {
            return .failure(Self.channelOccupancyMismatch)
          }
          return .success(true)
        }
        if isCancelled() { throw CancellationError() }
        if let floodScope {
          try await sendWithTemporaryFloodScope(
            message: message, floodScope: floodScope, isCancelled: isCancelled
          )
        } else {
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

  private func performChannelSend(
    channel: UInt8,
    text: String,
    timestamp: Date,
    floodScope: ResolvedFloodScope?
  ) async throws {
    let message = PacketBuilder.sendChannelMessage(channel: channel, text: text, timestamp: timestamp)
    try await performChannelCommand(message, floodScope: floodScope)
  }

  private func performChannelCommand(_ message: Data, floodScope: ResolvedFloodScope?) async throws {
    let cancelled = Mutex(false)
    let isCancelled: @Sendable () -> Bool = { cancelled.withLock { $0 } }
    try await withTaskCancellationHandler {
      if let floodScope {
        try await requestResponseSerializer.withSerialization(resumeCallerOnCancel: false) { [self] in
          try await self.sendWithTemporaryFloodScope(
            message: message, floodScope: floodScope, isCancelled: isCancelled
          )
        }
      } else {
        try await requestResponseSerializer.withSerialization { [self] in
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

  /// Keep the slot through the clear so no other command can inherit the override.
  /// An acknowledged channel text stays accepted when the clear gets no matching OK.
  private func sendWithTemporaryFloodScope(
    message: Data,
    floodScope: ResolvedFloodScope,
    isCancelled: @escaping @Sendable () -> Bool
  ) async throws {
    if isCancelled() { throw CancellationError() }

    // `send_scope` is one register for every interface, and this session cannot read it back.
    // Every zero-key send writes that command before the text.
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
    _ = await settleChannelCommand(Self.floodScopeBaselineCommand)
  }

  private static let channelOccupancyMismatch = MeshCoreError.invalidResponse(
    expected: "unchanged channel occupancy", got: "different or missing channel occupancy"
  )

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

  /// A bare OK or error proves this write only after submission, so an earlier frame cannot match it.
  private func settleChannelCommand(
    _ data: Data,
    isCancelled: @Sendable () -> Bool = { false }
  ) async -> ChannelCommandSettlement {
    let window = SubmittedCommandWindow()
    let (subscriptionID, events) = await dispatcher.subscribeTracked { event in
      window.isOpen && Self.isTerminalCommandReply(event)
    }
    let collector = Task { () -> ChannelCommandSettlement.Reply? in
      for await event in events {
        if let reply = Self.channelCommandReply(event) { return reply }
      }
      return nil
    }

    let writeError: Error?
    do {
      try await sendSessionData(data, beforeSend: {
        if isCancelled() { throw CancellationError() }
      }, notifyingSubmitted: { window.begin() })
      writeError = nil
    } catch {
      writeError = error
    }

    if let writeError {
      let submitted = window.isOpen && Self.replyCanProveFailedWrite(writeError)
      if submitted {
        // Give the receive loop a chance to publish a buffered reply before closing the subscription.
        await Task.yield()
      }
      await dispatcher.finishSubscription(id: subscriptionID)
      let reply = await collector.value
      if !submitted {
        return .notSubmitted(writeError)
      }
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
