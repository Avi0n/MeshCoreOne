import Foundation
import os

public extension MeshCoreSession {
  // MARK: - Status Requests

  /// Requests status information from a remote node via `CMD_SEND_STATUS_REQ`.
  ///
  /// Raw public-key status requests use the repeater status layout.
  /// For room servers, prefer ``requestStatus(from: MeshContact)`` or
  /// ``requestStatus(from:type:)`` so the correct status layout is selected.
  ///
  /// - Parameter publicKey: The full 32-byte public key of the remote node.
  /// - Returns: A status response containing battery, uptime, and other metrics.
  /// - Throws: ``MeshCoreError/timeout`` if no response within the timeout period.
  ///           ``MeshCoreError/deviceError(code:)`` if the device rejects the request.
  ///           ``MeshCoreError/invalidResponse`` if an unexpected response is received.
  func requestStatus(from publicKey: Data) async throws -> StatusResponse {
    try requireFullPublicKey(publicKey, operation: "requestStatus")
    return try await requestResponseSerializer.withSerialization { [self] in
      try await performStatusRequest(from: publicKey, layout: .repeater)
    }
  }

  /// Requests status information from a remote node via `CMD_SEND_STATUS_REQ`.
  ///
  /// - Parameters:
  ///   - publicKey: The full 32-byte public key of the remote node.
  ///   - type: The target node type used to choose the correct firmware status layout.
  /// - Returns: A status response containing battery, uptime, and other metrics.
  /// - Throws: ``MeshCoreError/timeout`` if no response within the timeout period.
  ///           ``MeshCoreError/deviceError(code:)`` if the device rejects the request.
  ///           ``MeshCoreError/invalidResponse`` if an unexpected response is received.
  func requestStatus(
    from publicKey: Data,
    type: ContactType
  ) async throws -> StatusResponse {
    try requireFullPublicKey(publicKey, operation: "requestStatus")
    let layout: StatusResponse.Layout = type == .room ? .roomServer : .repeater
    return try await requestResponseSerializer.withSerialization { [self] in
      try await performStatusRequest(from: publicKey, layout: layout)
    }
  }

  /// Requests status information from a remote contact using its contact type to
  /// select the correct firmware status layout.
  ///
  /// - Parameter contact: The remote contact to query.
  /// - Returns: A status response containing battery, uptime, and other metrics.
  /// - Throws: ``MeshCoreError`` if the request fails.
  func requestStatus(from contact: MeshContact) async throws -> StatusResponse {
    try await requestStatus(from: contact.publicKey, type: contact.type)
  }

  /// Internal implementation of status request, called within serialization.
  ///
  /// Uses `CMD_SEND_STATUS_REQ` so firmware pushes `STATUS_RESPONSE` (0x87)
  /// matched by public-key prefix. `parseFromBinaryResponse` is the tag-matched fallback.
  private func performStatusRequest(
    from publicKey: Data,
    layout: StatusResponse.Layout
  ) async throws -> StatusResponse {
    let publicKeyPrefix = Data(publicKey.prefix(6))
    return try await performBinaryExchange(
      request: PacketBuilder.sendStatusRequest(to: publicKey),
      to: publicKey,
      operation: "Status",
      matchRoutedEvent: { event in
        guard case let .statusResponse(response) = event,
              response.publicKeyPrefix == publicKeyPrefix else { return nil }
        if layout == .roomServer, response.layout == .repeater {
          return Self.roomServerStatus(fromRepeaterLayout: response)
        }
        return response
      }
    ) { payload, _ in
      Parsers.StatusResponse.parseFromBinaryResponse(
        payload,
        publicKeyPrefix: publicKeyPrefix,
        layout: layout
      )
    }
  }

  /// Maps a repeater-layout status push into room-server counters when the
  /// request used the room layout but the typed push arrived as repeater.
  private static func roomServerStatus(fromRepeaterLayout response: StatusResponse) -> StatusResponse {
    StatusResponse(
      layout: .roomServer,
      publicKeyPrefix: response.publicKeyPrefix,
      battery: response.battery,
      txQueueLength: response.txQueueLength,
      noiseFloor: response.noiseFloor,
      lastRSSI: response.lastRSSI,
      packetsReceived: response.packetsReceived,
      packetsSent: response.packetsSent,
      airtime: response.airtime,
      uptime: response.uptime,
      sentFlood: response.sentFlood,
      sentDirect: response.sentDirect,
      receivedFlood: response.receivedFlood,
      receivedDirect: response.receivedDirect,
      fullEvents: response.fullEvents,
      lastSNR: response.lastSNR,
      directDuplicates: response.directDuplicates,
      floodDuplicates: response.floodDuplicates,
      rxAirtime: 0,
      receiveErrors: 0,
      roomServerPostedCount: UInt16(truncatingIfNeeded: response.rxAirtime),
      roomServerPostPushCount: UInt16(truncatingIfNeeded: response.rxAirtime >> 16)
    )
  }

  /// Requests status information from a remote node.
  ///
  /// - Parameter destination: The destination (contact or public key).
  /// - Returns: Status response from the remote node.
  /// - Throws: ``MeshCoreError`` on failure.
  func requestStatus(from destination: Destination) async throws -> StatusResponse {
    switch destination {
    case let .contact(contact):
      return try await requestStatus(from: contact)
    case .data, .hexString:
      let publicKey = try destination.fullPublicKey()
      return try await requestStatus(from: publicKey)
    }
  }

  // MARK: - Binary Protocol Commands

  /// Requests telemetry data from a remote node via `CMD_SEND_TELEMETRY_REQ`.
  ///
  /// Firmware pushes `TELEMETRY_RESPONSE` (0x8B) matched by response tag.
  /// `parseFromBinaryResponse` is the tag-matched fallback.
  ///
  /// - Parameter publicKey: The full 32-byte public key of the remote node.
  /// - Returns: Telemetry response containing sensor data and device status.
  /// - Throws: ``MeshCoreError/timeout`` if no response within timeout period.
  ///           ``MeshCoreError/deviceError(code:)`` if the device rejects the request.
  ///           ``MeshCoreError/invalidResponse`` if unexpected response received.
  func requestTelemetry(from publicKey: Data) async throws -> TelemetryResponse {
    try requireFullPublicKey(publicKey, operation: "requestTelemetry")
    // Serialize binary requests to prevent messageSent race conditions
    return try await requestResponseSerializer.withSerialization { [self] in
      try await performTelemetryRequest(from: publicKey)
    }
  }

  /// Send `request` and resolve the first matching `.binaryResponse` or
  /// `matchRoutedEvent` hit. Retransmits the same frame until a reply arrives or
  /// `binaryRequestOverallTimeout` elapses (`nil` retransmit interval disables
  /// resends). Companion firmware keeps one pending tag per request class and
  /// replaces it on every send, so only the latest `messageSent` tag matches
  /// `.binaryResponse`. Routed pubkey matchers (status) ignore tags. Spacing is
  /// `max(floor, suggestedTimeoutMs × binaryRetransmitRTTHeadroom)`.
  ///
  /// - Parameters:
  ///   - request: The fully built request frame to send.
  ///   - publicKey: The destination node's public key; its 6-byte prefix labels log lines.
  ///   - operation: Request name for logging (e.g. "Status").
  ///   - matchRoutedEvent: Optional matcher for a response that arrives as an
  ///     already-routed typed event instead of a raw `.binaryResponse`.
  ///   - parseResponse: Parses the matched `.binaryResponse` payload (with its tag).
  ///     Returning `nil` surfaces ``MeshCoreError/parseError`` with payload size.
  internal func performBinaryExchange<Response: Sendable>(
    request: Data,
    to publicKey: Data,
    operation: String,
    matchRoutedEvent: (@Sendable (MeshEvent) -> Response?)? = nil,
    parseResponse: @escaping @Sendable (_ payload: Data, _ tag: Data) throws -> Response?
  ) async throws -> Response {
    let prefixHex = publicKey.prefix(6).map { String(format: "%02x", $0) }.joined()
    let startTime = ContinuousClock.now
    let overallTimeout = configuration.binaryRequestOverallTimeout
    let retransmitFloor = configuration.binaryRequestRetransmitInterval
    let cadence = BinaryExchangeCadence(minimumSeconds: retransmitFloor ?? 0)

    let floorDesc = retransmitFloor.map { String(format: "%.1f", $0) } ?? "off"
    logger.info(
      "\(operation) request to \(prefixHex): sending (overall=\(String(format: "%.1f", overallTimeout))s, retransmitFloor=\(floorDesc)s)"
    )

    // Subscribe before sending to avoid the race where the response arrives
    // before the consumer is listening.
    let events = await dispatcher.subscribe()
    try await transport.send(request)

    return try await withThrowingTaskGroup(of: BinaryExchangeSignal<Response>.self) { group in
      group.addTask { [logger] in
        // Firmware replaces the single pending tag on each send; track only latest.
        var expectedTag: Data?
        var acceptedMessageSent = false
        var sendCount = 1

        for await event in events {
          if Task.isCancelled { return .idle }

          switch event {
          case let .messageSent(info):
            expectedTag = info.expectedAck
            acceptedMessageSent = true
            await cadence.applySuggestedTimeoutMs(info.suggestedTimeoutMs)
            let tagHex = info.expectedAck.map { String(format: "%02x", $0) }.joined()
            logger.info(
              "\(operation) request to \(prefixHex): messageSent #\(sendCount) tag=\(tagHex) suggestedTimeoutMs=\(info.suggestedTimeoutMs)"
            )
            sendCount += 1

          case let .error(code):
            // Device errors after a live messageSent must not abort the wait
            // (retransmit can fail while an earlier attempt is still answerable).
            if !acceptedMessageSent {
              throw MeshCoreError.deviceError(code: code ?? 0)
            }
            logger.warning(
              "\(operation) request to \(prefixHex): device error \(code ?? 0) after messageSent; keeping wait"
            )
            continue

          case let .binaryResponse(tag, responseData):
            guard tag == expectedTag else { continue }

            guard let response = try parseResponse(responseData, tag) else {
              let preview = responseData.prefix(32).hexString
              logger.warning(
                "\(operation) request to \(prefixHex): binary response parse failed (\(responseData.count) bytes, prefix=\(preview))"
              )
              throw MeshCoreError.parseError(
                "\(operation) binary response unparseable (\(responseData.count) bytes)"
              )
            }
            let elapsed = ContinuousClock.now - startTime
            logger.info("\(operation) request to \(prefixHex): response received in \(elapsed)")
            return .response(response)

          default:
            if let routed = matchRoutedEvent?(event) {
              let elapsed = ContinuousClock.now - startTime
              logger.info("\(operation) request to \(prefixHex): routed response received in \(elapsed)")
              return .response(routed)
            }
            continue
          }
        }
        return .idle
      }

      group.addTask { [logger, clock = self.clock] in
        try await clock.sleep(for: .seconds(overallTimeout))
        let elapsed = ContinuousClock.now - startTime
        logger.warning("\(operation) request to \(prefixHex): timed out after \(elapsed)")
        return .timedOut
      }

      if retransmitFloor != nil {
        group.addTask { [logger, clock = self.clock, transport] in
          var attempt = 1
          while !Task.isCancelled {
            let delay = await cadence.waitForInterval()
            do {
              try await clock.sleep(for: .seconds(delay))
            } catch is CancellationError {
              return .idle
            }
            guard !Task.isCancelled else { break }
            attempt += 1
            let interval = await cadence.currentSeconds()
            logger.info(
              "\(operation) request to \(prefixHex): retransmit #\(attempt) (interval=\(String(format: "%.1f", interval))s)"
            )
            do {
              try await transport.send(request)
            } catch is CancellationError {
              return .idle
            } catch {
              logger.warning(
                "\(operation) request to \(prefixHex): retransmit send failed (\(error.localizedDescription)); continuing"
              )
            }
          }
          return .idle
        }
      }

      do {
        while let signal = try await group.next() {
          switch signal {
          case let .response(response):
            group.cancelAll()
            // Drain so a late retransmit send error cannot surface after success.
            while await (try? group.next()) != nil {}
            return response
          case .timedOut:
            group.cancelAll()
            throw MeshCoreError.timeout
          case .idle:
            continue
          }
        }
      } catch {
        group.cancelAll()
        throw error
      }
      throw MeshCoreError.timeout
    }
  }

  /// Internal implementation of telemetry request, called within serialization.
  private func performTelemetryRequest(from publicKey: Data) async throws -> TelemetryResponse {
    let publicKeyPrefix = Data(publicKey.prefix(6))
    // CMD_SEND_TELEMETRY_REQ frame: [0x27][3 reserved zeros][pubkey32]. Firmware
    // zeros the reserved mask so ~payload[1] grants full env permissions (guests
    // remain clamped to base telemetry on the repeater).
    return try await performBinaryExchange(
      request: PacketBuilder.getSelfTelemetry(destination: publicKey),
      to: publicKey,
      operation: "Telemetry",
      matchRoutedEvent: { event in
        guard case let .telemetryResponse(response) = event,
              response.publicKeyPrefix == publicKeyPrefix else { return nil }
        return response
      }
    ) { payload, _ in
      Parsers.TelemetryResponse.parseFromBinaryResponse(payload, publicKeyPrefix: publicKeyPrefix)
    }
  }

  /// Requests telemetry data from a destination.
  ///
  /// - Parameter destination: The destination (contact or public key).
  /// - Returns: Telemetry response from the remote node.
  /// - Throws: ``MeshCoreError`` on failure.
  func requestTelemetry(from destination: Destination) async throws -> TelemetryResponse {
    let publicKey = try destination.fullPublicKey()
    return try await requestTelemetry(from: publicKey)
  }

  // MARK: - Owner Info

  /// Requests owner information from a repeater using binary protocol.
  ///
  /// - Parameter publicKey: The full 32-byte public key of the repeater.
  /// - Returns: An ``OwnerInfoResponse`` containing firmware version, node name, and owner info.
  /// - Throws: ``MeshCoreError/timeout`` if no response within timeout period.
  func requestOwnerInfo(from publicKey: Data) async throws -> OwnerInfoResponse {
    try requireFullPublicKey(publicKey, operation: "requestOwnerInfo")
    return try await requestResponseSerializer.withSerialization { [self] in
      try await performOwnerInfoRequest(from: publicKey)
    }
  }

  /// Internal implementation of owner info request, called within serialization.
  private func performOwnerInfoRequest(from publicKey: Data) async throws -> OwnerInfoResponse {
    try await performBinaryExchange(
      request: PacketBuilder.binaryRequest(to: publicKey, type: .ownerInfo),
      to: publicKey,
      operation: "Owner info"
    ) { payload, _ in
      // Response is UTF-8: "<firmware_ver>\n<node_name>\n<owner_info>"
      let text = String(data: payload, encoding: .utf8) ?? ""
      let components = text.split(separator: "\n", maxSplits: 2, omittingEmptySubsequences: false)
      return OwnerInfoResponse(
        firmwareVersion: components.count >= 1 ? String(components[0]) : "",
        nodeName: components.count >= 2 ? String(components[1]) : "",
        ownerInfo: components.count >= 3 ? String(components[2]) : ""
      )
    }
  }

  /// Requests Min-Max-Average (MMA) data for a time range.
  ///
  /// Retrieves aggregated sensor data statistics from a remote node.
  ///
  /// - Parameters:
  ///   - publicKey: The full 32-byte public key of the remote node.
  ///   - start: Start of the time range.
  ///   - end: End of the time range.
  /// - Returns: MMA response containing aggregated statistics.
  /// - Throws: ``MeshCoreError/timeout`` if no response within timeout period.
  ///           ``MeshCoreError/deviceError(code:)`` if the device rejects the request.
  func requestMMA(from publicKey: Data, start: Date, end: Date) async throws -> MMAResponse {
    try requireFullPublicKey(publicKey, operation: "requestMMA")
    return try await requestResponseSerializer.withSerialization { [self] in
      try await performMMARequest(from: publicKey, start: start, end: end)
    }
  }

  /// Internal implementation of MMA request, called within serialization.
  private func performMMARequest(from publicKey: Data, start: Date, end: Date) async throws -> MMAResponse {
    // Build payload
    var payload = Data()
    let startTimestamp = PacketBuilder.epochSeconds32(start)
    let endTimestamp = PacketBuilder.epochSeconds32(end)
    payload.append(contentsOf: withUnsafeBytes(of: startTimestamp.littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: endTimestamp.littleEndian) { Array($0) })
    payload.append(contentsOf: [0, 0])

    let publicKeyPrefix = Data(publicKey.prefix(6))
    return try await performBinaryExchange(
      request: PacketBuilder.binaryRequest(to: publicKey, type: .mma, payload: payload),
      to: publicKey,
      operation: "MMA"
    ) { payload, tag in
      MMAResponse(publicKeyPrefix: publicKeyPrefix, tag: tag, data: MMAParser.parse(payload))
    }
  }

  /// Requests the Access Control List (ACL) from a remote node.
  ///
  /// Retrieves the list of authorized public keys for administrative access.
  ///
  /// - Parameter publicKey: The full 32-byte public key of the remote node.
  /// - Returns: ACL response containing authorized public keys.
  /// - Throws: ``MeshCoreError/timeout`` if no response within timeout period.
  ///           ``MeshCoreError/deviceError(code:)`` if the device rejects the request.
  func requestACL(from publicKey: Data) async throws -> ACLResponse {
    try requireFullPublicKey(publicKey, operation: "requestACL")
    return try await requestResponseSerializer.withSerialization { [self] in
      try await performACLRequest(from: publicKey)
    }
  }

  /// Internal implementation of ACL request, called within serialization.
  private func performACLRequest(from publicKey: Data) async throws -> ACLResponse {
    let payload = Data([0, 0])
    let publicKeyPrefix = Data(publicKey.prefix(6))
    return try await performBinaryExchange(
      request: PacketBuilder.binaryRequest(to: publicKey, type: .acl, payload: payload),
      to: publicKey,
      operation: "ACL"
    ) { payload, tag in
      ACLResponse(publicKeyPrefix: publicKeyPrefix, tag: tag, entries: ACLParser.parse(payload))
    }
  }

  /// Requests the neighbor list from a remote node.
  ///
  /// Retrieves information about nodes that the remote device can directly communicate with.
  ///
  /// - Parameters:
  ///   - publicKey: The full 32-byte public key of the remote node.
  ///   - count: Maximum number of neighbors to return (default 255).
  ///   - offset: Starting offset for pagination (default 0).
  ///   - orderBy: Sort order (0 = by RSSI, default).
  ///   - pubkeyPrefixLength: Length of public key prefix to include (default 4).
  /// - Returns: Neighbors response containing list of adjacent nodes.
  /// - Throws: ``MeshCoreError/timeout`` if no response within timeout period.
  ///           ``MeshCoreError/deviceError(code:)`` if the device rejects the request.
  func requestNeighbours(
    from publicKey: Data,
    count: UInt8 = 255,
    offset: UInt16 = 0,
    orderBy: UInt8 = 0,
    pubkeyPrefixLength: UInt8 = 4
  ) async throws -> NeighboursResponse {
    try requireFullPublicKey(publicKey, operation: "requestNeighbours")
    return try await requestResponseSerializer.withSerialization { [self] in
      try await performNeighboursRequest(
        from: publicKey,
        count: count,
        offset: offset,
        orderBy: orderBy,
        pubkeyPrefixLength: pubkeyPrefixLength
      )
    }
  }

  /// Internal implementation of neighbours request, called within serialization.
  private func performNeighboursRequest(
    from publicKey: Data,
    count: UInt8,
    offset: UInt16,
    orderBy: UInt8,
    pubkeyPrefixLength: UInt8
  ) async throws -> NeighboursResponse {
    var payload = Data()
    payload.append(0) // version
    payload.append(count)
    payload.append(contentsOf: withUnsafeBytes(of: offset.littleEndian) { Array($0) })
    payload.append(orderBy)
    payload.append(pubkeyPrefixLength)
    let randomTag = UInt32.random(in: 1...UInt32.max)
    payload.append(contentsOf: withUnsafeBytes(of: randomTag.littleEndian) { Array($0) })

    let publicKeyPrefix = Data(publicKey.prefix(6))
    let prefixLength = Int(pubkeyPrefixLength)
    return try await performBinaryExchange(
      request: PacketBuilder.binaryRequest(to: publicKey, type: .neighbours, payload: payload),
      to: publicKey,
      operation: "Neighbours"
    ) { payload, tag in
      NeighboursParser.parse(
        payload,
        publicKeyPrefix: publicKeyPrefix,
        tag: tag,
        prefixLength: prefixLength
      )
    }
  }

  /// Fetches all neighbors from a remote node with automatic pagination.
  ///
  /// This is a convenience method that automatically handles pagination to retrieve
  /// the complete neighbor list, making multiple requests if necessary.
  ///
  /// - Parameters:
  ///   - publicKey: The full 32-byte public key of the remote node.
  ///   - orderBy: Sort order (0 = by RSSI, default).
  ///   - pubkeyPrefixLength: Length of public key prefix to include (default 4).
  /// - Returns: Complete neighbors response with all neighbors.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/invalidResponse`` on failure.
  func fetchAllNeighbours(
    from publicKey: Data,
    orderBy: UInt8 = 0,
    pubkeyPrefixLength: UInt8 = 4
  ) async throws -> NeighboursResponse {
    try await NeighboursResponse.collectingAllPages { offset in
      if offset > 0 { try await clock.sleep(for: NeighboursResponse.interPageDelay) }
      return try await requestNeighbours(
        from: publicKey,
        count: 255,
        offset: offset,
        orderBy: orderBy,
        pubkeyPrefixLength: pubkeyPrefixLength
      )
    }
  }
}

/// Task-group signal for `performBinaryExchange` (file scope: nested enums are
/// not allowed inside generic methods).
private enum BinaryExchangeSignal<Response: Sendable>: Sendable {
  case response(Response)
  case timedOut
  /// Cancelled retransmit loop or ended event stream; not a terminal result.
  case idle
}

/// Live retransmit spacing shared from `messageSent` into the resend loop.
/// Floor starts at the config minimum; rises to
/// `suggestedTimeoutMs × binaryRetransmitRTTHeadroom` so multi-hop waits cover
/// a full return trip before another copy.
private actor BinaryExchangeCadence {
  private var seconds: TimeInterval
  private var hasSuggested = false
  private var waiters: [CheckedContinuation<TimeInterval, Never>] = []

  init(minimumSeconds: TimeInterval) {
    self.seconds = minimumSeconds
  }

  func applySuggestedTimeoutMs(_ ms: UInt32) {
    let suggested = TimeInterval(ms) / SessionConfiguration.millisecondsPerSecond
    let withHeadroom = suggested * SessionConfiguration.binaryRetransmitRTTHeadroom
    if withHeadroom > seconds {
      seconds = withHeadroom
    }
    hasSuggested = true
    let ready = waiters
    waiters.removeAll()
    for waiter in ready {
      waiter.resume(returning: seconds)
    }
  }

  /// Resolves with the live interval once firmware has suggested an RTT.
  /// Falls back to the configured floor if cancelled before `messageSent`.
  func waitForInterval() async -> TimeInterval {
    if hasSuggested || Task.isCancelled {
      return seconds
    }
    return await withTaskCancellationHandler {
      await withCheckedContinuation { continuation in
        if hasSuggested {
          continuation.resume(returning: seconds)
        } else {
          waiters.append(continuation)
        }
      }
    } onCancel: {
      Task { await self.resumeWaitersWithFloor() }
    }
  }

  func currentSeconds() -> TimeInterval {
    seconds
  }

  private func resumeWaitersWithFloor() {
    let ready = waiters
    waiters.removeAll()
    for waiter in ready {
      waiter.resume(returning: seconds)
    }
  }
}
