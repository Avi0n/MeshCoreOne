import Foundation
import os

public extension MeshCoreSession {
  // MARK: - Messaging Commands

  /// Sends a text message to a contact.
  ///
  /// - Parameters:
  ///   - destination: The destination public key (6+ bytes, uses first 6 as prefix).
  ///   - text: The message text to send.
  ///   - timestamp: Message timestamp. Defaults to current time.
  ///   - attempt: Retry attempt counter (0 for first attempt). Included in ACK hash.
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: ``MeshCoreError/timeout`` if no response.
  ///           ``MeshCoreError/deviceError(code:)`` on error.
  func sendMessage(
    to destination: Data,
    text: String,
    timestamp: Date = Date(),
    attempt: UInt8 = 0
  ) async throws -> MessageSentInfo {
    let data = PacketBuilder.sendMessage(to: destination, text: text, timestamp: timestamp, attempt: attempt)
    return try await sendAndWaitWithError(
      data,
      matching: { event in
        if case let .messageSent(info) = event { return info }
        return nil
      },
      errorMatcher: Self.deviceErrorMatcher
    )
  }

  /// Sends a text message to a destination.
  ///
  /// - Parameters:
  ///   - destination: The destination (contact or public key).
  ///   - text: The message text to send.
  ///   - timestamp: Message timestamp. Defaults to current time.
  ///   - attempt: Retry attempt counter (0 for first attempt). Included in ACK hash.
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: ``MeshCoreError`` on failure.
  func sendMessage(
    to destination: Destination,
    text: String,
    timestamp: Date = Date(),
    attempt: UInt8 = 0
  ) async throws -> MessageSentInfo {
    let publicKey = try destination.publicKey(prefixLength: 6)
    return try await sendMessage(to: publicKey, text: text, timestamp: timestamp, attempt: attempt)
  }

  /// Sends a message with automatic retry logic and optional path reset.
  ///
  /// This method attempts to send a message multiple times. If initial attempts fail,
  /// it can optionally reset the routing path to "flood" mode to increase delivery
  /// probability.
  ///
  /// - Parameters:
  ///   - destination: The full 32-byte public key of the recipient. A full key is
  ///                  required if path reset is enabled.
  ///   - text: The message text to send.
  ///   - timestamp: The message timestamp. Defaults to current time.
  ///   - maxAttempts: The maximum number of total attempts to make. Defaults to 3.
  ///   - floodAfter: The number of failed attempts after which to reset the path to flood.
  ///                 Defaults to 2.
  ///   - maxFloodAttempts: The maximum number of attempts to make while in flood mode.
  ///                       Defaults to 2.
  ///   - timeout: The acknowledgment timeout per attempt. If `nil`, uses the suggested
  ///              timeout provided by the device.
  /// - Returns: Information about the sent message if an acknowledgment was received,
  ///            otherwise `nil` if all attempts failed.
  /// - Throws: ``MeshCoreError/invalidInput`` if the destination key is not 32 bytes.
  func sendMessageWithRetry(
    to destination: Data,
    text: String,
    timestamp: Date = Date(),
    maxAttempts: Int = 3,
    floodAfter: Int = 2,
    maxFloodAttempts: Int = 2,
    timeout: TimeInterval? = nil
  ) async throws -> MessageSentInfo? {
    guard destination.count >= PacketBuilder.publicKeySize else {
      throw MeshCoreError.invalidInput("Full \(PacketBuilder.publicKeySize)-byte public key required for retry with path reset")
    }

    var attempts = 0
    var floodAttempts = 0
    var isFloodMode = false

    while attempts < maxAttempts, !isFloodMode || floodAttempts < maxFloodAttempts {
      if attempts == floodAfter, !isFloodMode {
        logger.info("Resetting path to flood after \(attempts) failed attempts")
        do {
          try await resetPath(publicKey: destination)
          isFloodMode = true
        } catch {
          logger.warning("Failed to reset path: \(error.localizedDescription), continuing...")
        }
      }

      if attempts > 0 {
        logger.info("Retry sending message: attempt \(attempts + 1)/\(maxAttempts)")
      }

      let sentInfo = try await sendMessage(to: destination.prefix(6), text: text, timestamp: timestamp, attempt: UInt8(attempts))

      let ackTimeout = timeout ?? (
        Double(sentInfo.suggestedTimeoutMs) / SessionConfiguration.millisecondsPerSecond
          * SessionConfiguration.retryAckTimeoutMultiplier
      )
      let ackEvent = await waitForEvent(matching: { event in
        if case let .acknowledgement(code, _) = event {
          return code == sentInfo.expectedAck
        }
        return false
      }, timeout: ackTimeout)

      if ackEvent != nil {
        logger.info("Message acknowledged on attempt \(attempts + 1)")
        return sentInfo
      }

      attempts += 1
      if isFloodMode {
        floodAttempts += 1
      }
    }

    logger.warning("Message delivery failed after \(attempts) attempts")
    return nil
  }

  /// Sends an advertisement broadcast.
  ///
  /// - Parameter flood: If `true`, the advertisement is broadcast using flood routing.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func sendAdvertisement(flood: Bool = false) async throws {
    try await sendSimpleCommand(PacketBuilder.sendAdvertisement(flood: flood))
  }

  /// Fetches the next pending message from the device.
  ///
  /// Returns one message at a time from the device's message queue. Call repeatedly
  /// until `.noMoreMessages` is returned to drain the queue.
  /// Use ``startAutoMessageFetching()`` to automate this process.
  ///
  /// - Parameter timeout: Optional timeout override in seconds. Uses `configuration.defaultTimeout` when `nil`.
  ///                      When a fetch is already in flight, the call coalesces onto it and this
  ///                      value is not observed; the wait is bounded by the in-flight fetch's timeout.
  /// - Returns: A ``MessageResult`` containing either a contact message, channel message,
  ///            channel datagram (firmware v11+), or ``MessageResult/noMoreMessages``.
  /// - Throws: ``MeshCoreError`` if the fetch fails.
  func getMessage(timeout: TimeInterval? = nil) async throws -> MessageResult {
    // Calls arriving while a fetch is in flight coalesce onto the same task and
    // share its outcome, so every coalesced caller's wait is bounded by the
    // leader's timeout and released on every completion path. The exchange runs
    // in its own task so a cancelled caller cannot abandon it mid-wire; late
    // responses still resolve inside the serializer instead of leaking.
    if let inFlight = inFlightGetMessage {
      return try await inFlight.value
    }

    let task = Task { try await performGetMessage(timeout: timeout) }
    inFlightGetMessage = task
    defer { inFlightGetMessage = nil }
    return try await task.value
  }

  private func performGetMessage(timeout: TimeInterval? = nil) async throws -> MessageResult {
    try await requestResponseSerializer.withSerialization { [self] in
      let timeoutSeconds = timeout ?? configuration.defaultTimeout

      // Subscribe before sending, then only accept an `.error` once the getMessage
      // frame has gone out. Holding the serializer makes this the sole exchange in
      // flight, so the only error that can arrive inside the window is the device's
      // reply to this request; without that guard a concurrent command's error frame
      // could be consumed here as a spurious message-fetch failure.
      let stream = await dispatcher.subscribe { event in
        switch event {
        case .contactMessageReceived, .channelMessageReceived, .channelDataReceived,
             .noMoreMessages, .error:
          true
        default:
          false
        }
      }

      let data = PacketBuilder.getMessage()
      try await transport.send(data)

      return try await withThrowingTaskGroup(of: MessageResult.self) { group in
        group.addTask {
          for await event in stream {
            if Task.isCancelled {
              throw CancellationError()
            }

            switch event {
            case let .contactMessageReceived(msg):
              return .contactMessage(msg)
            case let .channelMessageReceived(msg):
              return .channelMessage(msg)
            case let .channelDataReceived(dg):
              return .channelDatagram(dg)
            case .noMoreMessages:
              return .noMoreMessages
            case let .error(code):
              throw MeshCoreError.deviceError(code: code ?? 0)
            default:
              continue
            }
          }

          throw MeshCoreError.timeout
        }

        group.addTask { [clock = self.clock] in
          try await clock.sleep(for: .seconds(timeoutSeconds))
          throw MeshCoreError.timeout
        }

        defer { group.cancelAll() }

        guard let result = try await group.next() else {
          throw MeshCoreError.timeout
        }

        return result
      }
    }
  }

  /// Sends a command message to a remote node.
  ///
  /// Commands are special messages that trigger actions on the remote device.
  ///
  /// - Parameters:
  ///   - destination: The destination public key (6+ bytes, uses first 6 as prefix).
  ///   - command: The command string to send.
  ///   - timestamp: Message timestamp. Defaults to current time.
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func sendCommand(
    to destination: Data,
    command: String,
    timestamp: Date = Date()
  ) async throws -> MessageSentInfo {
    try await sendAndWaitWithError(
      PacketBuilder.sendCommand(to: destination, command: command, timestamp: timestamp),
      matching: { event in
        if case let .messageSent(info) = event { return info }
        return nil
      },
      errorMatcher: Self.deviceErrorMatcher
    )
  }

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
    try await sendSimpleCommand(PacketBuilder.sendChannelMessage(channel: channel, text: text, timestamp: timestamp))
  }

  /// Sends a binary datagram to a channel.
  ///
  /// Requires firmware v11+ (MeshCore v1.15.0+).
  ///
  /// - Parameters:
  ///   - channelIndex: Channel slot index.
  ///   - dataType: Application data-type namespace. `0x0000` is reserved and rejected by
  ///     firmware; `0xFFFF` is the developer namespace.
  ///   - payload: Binary payload (clamped to 163 bytes by ``PacketBuilder/sendChannelData(channelIndex:dataType:payload:pathLength:pathBytes:)``).
  ///   - pathLength: Encoded `path_len` byte. Defaults to ``PacketBuilder/floodPathSentinel``
  ///     (`0xFF` = flood). Non-flood values must satisfy firmware's `Packet::isValidPathLen`;
  ///     callers working from a ``MeshContact`` can pass `contact.outPathLength` directly.
  ///   - pathBytes: Path bytes written verbatim. Ignored when `pathLength == 0xFF`. Callers
  ///     working from a ``MeshContact`` can pass `contact.outPath` directly.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func sendChannelData(
    channelIndex: UInt8,
    dataType: UInt16,
    payload: Data,
    pathLength: UInt8 = PacketBuilder.floodPathSentinel,
    pathBytes: Data = Data()
  ) async throws {
    try await sendSimpleCommand(
      PacketBuilder.sendChannelData(
        channelIndex: channelIndex,
        dataType: dataType,
        payload: payload,
        pathLength: pathLength,
        pathBytes: pathBytes
      )
    )
  }

  /// Sends a login request to a remote node.
  ///
  /// Authenticates with a password-protected node to gain administrative access.
  ///
  /// - Parameters:
  ///   - destination: The node's public key (6+ bytes).
  ///   - password: The authentication password.
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func sendLogin(to destination: Data, password: String) async throws -> MessageSentInfo {
    try await sendAndWaitWithError(
      PacketBuilder.sendLogin(to: destination, password: password),
      matching: { event in
        if case let .messageSent(info) = event { return info }
        return nil
      },
      errorMatcher: Self.deviceErrorMatcher
    )
  }

  /// Sends a login request to a remote node.
  ///
  /// - Parameters:
  ///   - destination: The destination (contact or public key).
  ///   - password: The authentication password.
  /// - Returns: Information about the sent message.
  /// - Throws: ``MeshCoreError`` on failure.
  func sendLogin(to destination: Destination, password: String) async throws -> MessageSentInfo {
    let publicKey = try destination.fullPublicKey()
    return try await sendLogin(to: publicKey, password: password)
  }

  /// Sends a logout request to a remote node.
  ///
  /// Terminates an authenticated session with a remote node.
  ///
  /// - Parameter destination: The node's public key (6+ bytes).
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func sendLogout(to destination: Data) async throws {
    try await sendSimpleCommand(PacketBuilder.sendLogout(to: destination))
  }

  /// Requests status information from a remote node.
  ///
  /// - Parameter destination: The node's public key (6+ bytes).
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func sendStatusRequest(to destination: Data) async throws -> MessageSentInfo {
    try await sendAndWaitWithError(
      PacketBuilder.sendStatusRequest(to: destination),
      matching: { event in
        if case let .messageSent(info) = event { return info }
        return nil
      },
      errorMatcher: Self.deviceErrorMatcher
    )
  }

  /// Requests telemetry data from a remote node.
  ///
  /// - Parameter destination: The node's public key (6+ bytes).
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func sendTelemetryRequest(to destination: Data) async throws -> MessageSentInfo {
    try await sendAndWaitWithError(
      PacketBuilder.getSelfTelemetry(destination: destination),
      matching: { event in
        if case let .messageSent(info) = event { return info }
        return nil
      },
      errorMatcher: Self.deviceErrorMatcher
    )
  }

  /// Initiates path discovery to a remote node.
  ///
  /// Triggers route discovery to find or refresh the path to a destination.
  ///
  /// - Parameter destination: The node's public key (6+ bytes).
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func sendPathDiscovery(to destination: Data) async throws -> MessageSentInfo {
    try await sendAndWaitWithError(
      PacketBuilder.sendPathDiscovery(to: destination),
      matching: { event in
        if case let .messageSent(info) = event { return info }
        return nil
      },
      errorMatcher: Self.deviceErrorMatcher
    )
  }

  /// Sends a trace packet through the mesh network.
  ///
  /// Trace packets record the path they traverse, useful for network debugging.
  ///
  /// - Parameters:
  ///   - tag: Optional trace identifier. Random value generated if nil.
  ///   - authCode: Optional authentication code. Random value generated if nil.
  ///   - flags: Trace flags controlling behavior.
  ///   - path: Optional initial path to follow.
  /// - Returns: Information about the sent message, including tag and auth code.
  /// - Throws: ``MeshCoreError/invalidInput`` if `path` is `nil` or empty; firmware
  ///   requires at least one path byte and rejects a path-less trace frame.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func sendTrace(
    tag: UInt32? = nil,
    authCode: UInt32? = nil,
    flags: UInt8 = 0,
    path: Data? = nil
  ) async throws -> MessageSentInfo {
    guard let path, !path.isEmpty else {
      throw MeshCoreError.invalidInput("Trace requires at least one path byte")
    }

    let actualTag = tag ?? UInt32.random(in: 1...UInt32.max)
    let actualAuth = authCode ?? UInt32.random(in: 1...UInt32.max)

    return try await sendAndWaitWithError(
      PacketBuilder.sendTrace(tag: actualTag, authCode: actualAuth, flags: flags, path: path),
      matching: { event in
        if case let .messageSent(info) = event { return info }
        return nil
      },
      errorMatcher: Self.deviceErrorMatcher
    )
  }

  /// Sets the flood scope using a raw scope key.
  ///
  /// The flood scope limits broadcast flooding to nodes with matching scope.
  ///
  /// - Parameter scopeKey: The 32-byte scope key.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setFloodScope(scopeKey: Data) async throws {
    try await sendSimpleCommand(PacketBuilder.setFloodScope(scopeKey))
  }

  /// Sets the flood scope using a ``FloodScope`` enum.
  ///
  /// - Parameter scope: The flood scope to set.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setFloodScope(_ scope: FloodScope) async throws {
    try await setFloodScope(scopeKey: scope.scopeKey())
  }

  /// Forces un-scoped flood broadcasts, overriding the device's persisted default
  /// flood scope.
  ///
  /// Unlike ``setFloodScope(_:)`` (which resets the session scope and lets the device
  /// fall back to its default), this sets the firmware `send_unscoped` flag so flood
  /// packets are sent to all regions regardless of the configured default.
  ///
  /// Requires firmware ver 12+. Older firmware has no handler for sub-command 1 and
  /// rejects the command with `ERR_CODE_UNSUPPORTED_CMD` (surfaced as
  /// ``MeshCoreError/deviceError(code:)``), so callers must gate on the reported
  /// firmware version before calling.
  ///
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setFloodScopeUnscoped() async throws {
    try await sendSimpleCommand(PacketBuilder.setFloodScopeUnscoped())
  }

  /// Persists the device's default flood scope.
  ///
  /// The default scope is applied by the device when sending flood packets if
  /// no session-scoped key has been set. Passing an empty name clears the persisted
  /// scope; the `scopeKey` argument is ignored in that case.
  ///
  /// Requires firmware v11+ (MeshCore v1.15.0+).
  ///
  /// - Parameters:
  ///   - name: Display name (up to 30 UTF-8 bytes; longer names are truncated). An empty
  ///     name clears the persisted scope regardless of the `scopeKey` value.
  ///   - scopeKey: 16-byte scope key (shorter keys are zero-padded). Ignored when `name`
  ///     is empty.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setDefaultFloodScope(name: String, scopeKey: Data) async throws {
    try await sendSimpleCommand(
      PacketBuilder.setDefaultFloodScope(name: name, scopeKey: scopeKey)
    )
  }

  /// Persists the device's default flood scope from a ``FloodScope``.
  ///
  /// - Parameters:
  ///   - name: Display name stored on the device.
  ///   - scope: The scope to persist. Passing ``FloodScope/disabled`` clears the scope.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setDefaultFloodScope(name: String, scope: FloodScope) async throws {
    try await sendSimpleCommand(
      PacketBuilder.setDefaultFloodScope(name: name, scope: scope)
    )
  }

  /// Fetches the device's persisted default flood scope.
  ///
  /// Requires firmware v11+ (MeshCore v1.15.0+). Older firmware will surface the unknown
  /// opcode as ``MeshCoreError/deviceError(code:)``.
  ///
  /// - Returns: The persisted scope, or `nil` if none is configured.
  /// - Throws: ``MeshCoreError/timeout`` if no response arrives;
  ///           ``MeshCoreError/deviceError(code:)`` if the device rejected the command.
  func getDefaultFloodScope() async throws -> DefaultFloodScope? {
    let data = PacketBuilder.getDefaultFloodScope()
    return try await sendAndMatch(data) { event in
      switch event {
      case let .defaultFloodScope(scope):
        .success(scope)
      case let .error(code):
        .failure(MeshCoreError.deviceError(code: code ?? 0))
      default:
        .ignore
      }
    }
  }

  /// Sets the path hash mode on the device.
  ///
  /// - Parameter mode: Hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes).
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setPathHashMode(_ mode: UInt8) async throws {
    guard mode <= UInt8(PathEncoding.maxPathHashMode) else {
      throw MeshCoreError.invalidInput("Path hash mode must be 0, 1, or 2")
    }
    try await sendSimpleCommand(PacketBuilder.setPathHashMode(mode))
  }

  // MARK: - Keep-Alive

  /// Sends a keep-alive request to a room server with the client's sync watermark.
  ///
  /// The companion radio passes the payload through to the mesh layer, producing:
  /// `[tag(4)][REQ_TYPE_KEEP_ALIVE(1)][sync_since(4)]` — 9 bytes total.
  ///
  /// The room server uses `sync_since` as a force-resync hint to update the client's
  /// message watermark. The normal push-and-ACK cycle also advances `sync_since`
  /// independently, so this serves as a correction mechanism.
  ///
  /// - Parameters:
  ///   - publicKey: The full 32-byte public key of the room server.
  ///   - syncSince: The client's last-received message timestamp (little-endian on wire).
  /// - Returns: Information about the sent message.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func sendKeepAlive(to publicKey: Data, syncSince: UInt32) async throws -> MessageSentInfo {
    try requireFullPublicKey(publicKey, operation: "sendKeepAlive")
    var syncSinceLE = syncSince.littleEndian
    let payload = withUnsafeBytes(of: &syncSinceLE) { Data($0) }
    let data = PacketBuilder.binaryRequest(to: publicKey, type: .keepAlive, payload: payload)
    return try await sendAndWaitWithError(
      data,
      matching: { event in
        if case let .messageSent(info) = event { return info }
        return nil
      },
      errorMatcher: Self.deviceErrorMatcher
    )
  }
}
