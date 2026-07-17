import Foundation
import os

public extension MeshCoreSession {
  // MARK: - Status Requests

  /// Requests status information from a remote node using the binary protocol.
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

  /// Requests status information from a remote node using the binary protocol.
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
  private func performStatusRequest(
    from publicKey: Data,
    layout: StatusResponse.Layout
  ) async throws -> StatusResponse {
    let publicKeyPrefix = Data(publicKey.prefix(6))
    return try await performBinaryExchange(
      request: PacketBuilder.binaryRequest(to: publicKey, type: .status),
      to: publicKey,
      operation: "Status",
      matchRoutedEvent: { event in
        guard case let .statusResponse(response) = event,
              response.publicKeyPrefix == publicKeyPrefix else { return nil }
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

  /// Requests telemetry data from a remote node using binary protocol.
  ///
  /// This uses the binary protocol for more efficient data transfer than text commands.
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

  /// Runs one binary-protocol exchange: subscribe, send `request`, learn the expected
  /// response tag and firmware-suggested timeout from `.messageSent`, then resolve the
  /// first `.binaryResponse` whose tag matches.
  ///
  /// The consumer finishes the timeout stream on every exit, including the `.error`
  /// throw, so the timeout task always starts its sleep instead of waiting on a
  /// stream nobody will finish.
  ///
  /// - Parameters:
  ///   - request: The fully built request frame to send.
  ///   - publicKey: The destination node's public key; its 6-byte prefix labels log lines.
  ///   - operation: Request name for logging (e.g. "Status").
  ///   - matchRoutedEvent: Optional matcher for a response that arrives as an
  ///     already-routed typed event instead of a raw `.binaryResponse`.
  ///   - parseResponse: Parses the matched `.binaryResponse` payload (with its tag).
  ///     Returning `nil` abandons the exchange and surfaces ``MeshCoreError/timeout``.
  internal func performBinaryExchange<Response: Sendable>(
    request: Data,
    to publicKey: Data,
    operation: String,
    matchRoutedEvent: (@Sendable (MeshEvent) -> Response?)? = nil,
    parseResponse: @escaping @Sendable (_ payload: Data, _ tag: Data) throws -> Response?
  ) async throws -> Response {
    let prefixHex = publicKey.prefix(6).map { String(format: "%02x", $0) }.joined()
    let startTime = ContinuousClock.now

    logger.info("\(operation) request to \(prefixHex): sending")

    // Subscribe before sending to avoid the race where the response arrives
    // before the consumer is listening.
    let events = await dispatcher.subscribe()
    try await transport.send(request)

    // Wait for messageSent (to get expectedAck) then binaryResponse (the actual response)
    return try await withThrowingTaskGroup(of: Response?.self) { group in
      let (timeoutStream, timeoutContinuation) = AsyncStream<TimeInterval>.makeStream()

      group.addTask { [logger, configuration] in
        var expectedAck: Data?

        for await event in events {
          if Task.isCancelled { return nil }

          switch event {
          case let .messageSent(info):
            // Capture the expectedAck from firmware's MSG_SENT response
            // and signal the dynamic timeout to the timeout task.
            expectedAck = info.expectedAck
            let timeout = configuration.binaryRequestTimeout(
              suggestedTimeoutMs: info.suggestedTimeoutMs
            )
            logger.info("\(operation) request to \(prefixHex): messageSent received, suggestedTimeoutMs=\(info.suggestedTimeoutMs), effective timeout=\(String(format: "%.1f", timeout))s")
            timeoutContinuation.yield(timeout)
            timeoutContinuation.finish()

          case let .error(code):
            timeoutContinuation.finish()
            throw MeshCoreError.deviceError(code: code ?? 0)

          case let .binaryResponse(tag, responseData):
            // Match by expectedAck (4-byte tag from firmware)
            guard let expected = expectedAck, tag == expected else { continue }

            guard let response = try parseResponse(responseData, tag) else {
              return nil
            }
            let elapsed = ContinuousClock.now - startTime
            logger.info("\(operation) request to \(prefixHex): response received in \(elapsed)")
            return response

          default:
            // Handle an already-routed response (if routing happens elsewhere)
            if let routed = matchRoutedEvent?(event) {
              let elapsed = ContinuousClock.now - startTime
              logger.info("\(operation) request to \(prefixHex): routed response received in \(elapsed)")
              return routed
            }
            continue
          }
        }
        timeoutContinuation.finish()
        return nil
      }

      group.addTask { [logger, clock = self.clock, defaultTimeout = configuration.defaultTimeout] in
        // Wait for dynamic timeout from event task, or use default
        var timeout = defaultTimeout
        var usedFirmwareTimeout = false
        for await t in timeoutStream {
          timeout = t
          usedFirmwareTimeout = true
          break
        }
        logger.info("\(operation) request to \(prefixHex): timeout task sleeping for \(String(format: "%.1f", timeout))s (\(usedFirmwareTimeout ? "firmware" : "default"))")
        try await clock.sleep(for: .seconds(timeout))
        let elapsed = ContinuousClock.now - startTime
        logger.warning("\(operation) request to \(prefixHex): timed out after \(elapsed)")
        return nil
      }

      if let result = try await group.next() ?? nil {
        group.cancelAll()
        return result
      }
      group.cancelAll()
      throw MeshCoreError.timeout
    }
  }

  /// Internal implementation of telemetry request, called within serialization.
  private func performTelemetryRequest(from publicKey: Data) async throws -> TelemetryResponse {
    // v1.12+ firmware reads payload[1] as an inverse permission mask.
    // 0x00 inverts to 0xFF (all permissions granted), plus 3 reserved bytes.
    let telemetryPayload = Data([0x00, 0x00, 0x00, 0x00])
    let publicKeyPrefix = Data(publicKey.prefix(6))
    return try await performBinaryExchange(
      request: PacketBuilder.binaryRequest(to: publicKey, type: .telemetry, payload: telemetryPayload),
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
