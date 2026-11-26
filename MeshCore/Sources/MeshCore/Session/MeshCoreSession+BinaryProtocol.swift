import Foundation
#if canImport(os)
  import os
#endif

public extension MeshCoreSession {
  // MARK: - Status Requests

  /// Requests status information from a remote node via `CMD_SEND_STATUS_REQ`.
  func requestStatus(
    from publicKey: Data,
    priority: MeshRequestPriority
  ) async throws -> StatusResponse {
    try requireFullPublicKey(publicKey, operation: "requestStatus")
    return try await performStatusRequest(from: publicKey, layout: .repeater, priority: priority)
  }

  /// Requests status information from a remote node via `CMD_SEND_STATUS_REQ`.
  func requestStatus(
    from publicKey: Data,
    type: ContactType,
    priority: MeshRequestPriority
  ) async throws -> StatusResponse {
    try requireFullPublicKey(publicKey, operation: "requestStatus")
    let layout: StatusResponse.Layout = type == .room ? .roomServer : .repeater
    return try await performStatusRequest(from: publicKey, layout: layout, priority: priority)
  }

  /// Requests status information from a remote contact using its contact type to
  /// select the correct firmware status layout.
  func requestStatus(
    from contact: MeshContact,
    priority: MeshRequestPriority
  ) async throws -> StatusResponse {
    try await requestStatus(from: contact.publicKey, type: contact.type, priority: priority)
  }

  private func performStatusRequest(
    from publicKey: Data,
    layout: StatusResponse.Layout,
    priority: MeshRequestPriority
  ) async throws -> StatusResponse {
    let publicKeyPrefix = Data(publicKey.prefix(6))
    let spec = MeshExchangeSpec<StatusResponse>(
      request: PacketBuilder.sendStatusRequest(to: publicKey),
      publicKey: publicKey,
      kind: .status,
      operation: "Status",
      acceptsBinaryResponse: true,
      routedEventFilter: { event in
        if case .statusResponse = event { return true }
        return false
      },
      matchRoutedEvent: { event in
        guard case let .statusResponse(response) = event,
              response.publicKeyPrefix == publicKeyPrefix else { return nil }
        if layout == .roomServer, response.layout == .repeater {
          return Self.roomServerStatus(fromRepeaterLayout: response)
        }
        return response
      },
      parseBinaryResponse: { payload, _ in
        Parsers.StatusResponse.parseFromBinaryResponse(
          payload,
          publicKeyPrefix: publicKeyPrefix,
          layout: layout
        )
      },
      budget: { [overall = configuration.binaryRequestOverallTimeout] _ in overall },
      onTimeoutKnown: nil,
      loginPasswordDigest: nil,
      samePasswordExemption: false,
      onUnconsumedExit: nil
    )
    return try await performMeshExchange(spec, priority: priority)
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
  func requestStatus(
    from destination: Destination,
    priority: MeshRequestPriority
  ) async throws -> StatusResponse {
    switch destination {
    case let .contact(contact):
      return try await requestStatus(from: contact, priority: priority)
    case .data, .hexString:
      let publicKey = try destination.fullPublicKey()
      return try await requestStatus(from: publicKey, priority: priority)
    }
  }

  // MARK: - Telemetry

  /// Requests telemetry data from a remote node via `CMD_SEND_TELEMETRY_REQ`.
  func requestTelemetry(
    from publicKey: Data,
    priority: MeshRequestPriority
  ) async throws -> TelemetryResponse {
    try requireFullPublicKey(publicKey, operation: "requestTelemetry")
    let publicKeyPrefix = Data(publicKey.prefix(6))
    let spec = MeshExchangeSpec<TelemetryResponse>(
      request: PacketBuilder.getSelfTelemetry(destination: publicKey),
      publicKey: publicKey,
      kind: .telemetry,
      operation: "Telemetry",
      acceptsBinaryResponse: true,
      routedEventFilter: { event in
        if case .telemetryResponse = event { return true }
        return false
      },
      matchRoutedEvent: { event in
        guard case let .telemetryResponse(response) = event,
              response.publicKeyPrefix == publicKeyPrefix else { return nil }
        return response
      },
      parseBinaryResponse: { payload, _ in
        Parsers.TelemetryResponse.parseFromBinaryResponse(payload, publicKeyPrefix: publicKeyPrefix)
      },
      budget: { [overall = configuration.binaryRequestOverallTimeout] _ in overall },
      onTimeoutKnown: nil,
      loginPasswordDigest: nil,
      samePasswordExemption: false,
      onUnconsumedExit: nil
    )
    return try await performMeshExchange(spec, priority: priority)
  }

  /// Requests telemetry data from a destination.
  func requestTelemetry(
    from destination: Destination,
    priority: MeshRequestPriority
  ) async throws -> TelemetryResponse {
    let publicKey = try destination.fullPublicKey()
    return try await requestTelemetry(from: publicKey, priority: priority)
  }

  // MARK: - Owner Info

  /// Requests owner information from a repeater using binary protocol.
  func requestOwnerInfo(
    from publicKey: Data,
    priority: MeshRequestPriority
  ) async throws -> OwnerInfoResponse {
    try requireFullPublicKey(publicKey, operation: "requestOwnerInfo")
    let spec = MeshExchangeSpec<OwnerInfoResponse>(
      request: PacketBuilder.binaryRequest(to: publicKey, type: .ownerInfo),
      publicKey: publicKey,
      kind: .binary,
      operation: "Owner info",
      acceptsBinaryResponse: true,
      routedEventFilter: { _ in false },
      matchRoutedEvent: nil,
      parseBinaryResponse: { payload, _ in
        // Response is UTF-8: "<firmware_ver>\n<node_name>\n<owner_info>".
        // Stop at the first NUL so encryption padding stays out of the text.
        let text = String(data: payload.prefix { $0 != 0 }, encoding: .utf8) ?? ""
        let components = text.split(separator: "\n", maxSplits: 2, omittingEmptySubsequences: false)
        return OwnerInfoResponse(
          firmwareVersion: components.count >= 1 ? String(components[0]) : "",
          nodeName: components.count >= 2 ? String(components[1]) : "",
          ownerInfo: components.count >= 3 ? String(components[2]) : ""
        )
      },
      budget: { [overall = configuration.binaryRequestOverallTimeout] _ in overall },
      onTimeoutKnown: nil,
      loginPasswordDigest: nil,
      samePasswordExemption: false,
      onUnconsumedExit: nil
    )
    return try await performMeshExchange(spec, priority: priority)
  }

  // MARK: - MMA

  /// Requests Min-Max-Average (MMA) data for a time range.
  func requestMMA(
    from publicKey: Data,
    start: Date,
    end: Date,
    priority: MeshRequestPriority
  ) async throws -> MMAResponse {
    try requireFullPublicKey(publicKey, operation: "requestMMA")
    let now = Date()
    func secondsAgo(_ date: Date) -> UInt32 {
      let seconds = now.timeIntervalSince(date)
      guard seconds.isFinite, seconds > 0 else { return 0 }
      guard seconds < Double(UInt32.max) else { return UInt32.max }
      return UInt32(seconds)
    }

    var payload = Data()
    let startSecondsAgo = secondsAgo(start)
    let endSecondsAgo = secondsAgo(end)
    payload.append(contentsOf: withUnsafeBytes(of: startSecondsAgo.littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: endSecondsAgo.littleEndian) { Array($0) })
    payload.append(contentsOf: [0, 0])

    let publicKeyPrefix = Data(publicKey.prefix(6))
    let spec = MeshExchangeSpec<MMAResponse>(
      request: PacketBuilder.binaryRequest(to: publicKey, type: .mma, payload: payload),
      publicKey: publicKey,
      kind: .binary,
      operation: "MMA",
      acceptsBinaryResponse: true,
      routedEventFilter: { _ in false },
      matchRoutedEvent: nil,
      parseBinaryResponse: { payload, tag in
        let serverClockSize = MemoryLayout<UInt32>.size
        guard payload.count >= serverClockSize else { return nil }
        return MMAResponse(
          publicKeyPrefix: publicKeyPrefix,
          tag: tag,
          data: MMAParser.parse(Data(payload.dropFirst(serverClockSize)))
        )
      },
      budget: { [overall = configuration.binaryRequestOverallTimeout] _ in overall },
      onTimeoutKnown: nil,
      loginPasswordDigest: nil,
      samePasswordExemption: false,
      onUnconsumedExit: nil
    )
    return try await performMeshExchange(spec, priority: priority)
  }

  // MARK: - ACL

  /// Requests the Access Control List (ACL) from a remote node.
  func requestACL(
    from publicKey: Data,
    priority: MeshRequestPriority
  ) async throws -> ACLResponse {
    try requireFullPublicKey(publicKey, operation: "requestACL")
    let payload = Data([0, 0])
    let publicKeyPrefix = Data(publicKey.prefix(6))
    let spec = MeshExchangeSpec<ACLResponse>(
      request: PacketBuilder.binaryRequest(to: publicKey, type: .acl, payload: payload),
      publicKey: publicKey,
      kind: .binary,
      operation: "ACL",
      acceptsBinaryResponse: true,
      routedEventFilter: { _ in false },
      matchRoutedEvent: nil,
      parseBinaryResponse: { payload, tag in
        ACLResponse(publicKeyPrefix: publicKeyPrefix, tag: tag, entries: ACLParser.parse(payload))
      },
      budget: { [overall = configuration.binaryRequestOverallTimeout] _ in overall },
      onTimeoutKnown: nil,
      loginPasswordDigest: nil,
      samePasswordExemption: false,
      onUnconsumedExit: nil
    )
    return try await performMeshExchange(spec, priority: priority)
  }

  // MARK: - Neighbours

  /// Requests the neighbor list from a remote node.
  func requestNeighbours(
    from publicKey: Data,
    count: UInt8 = 255,
    offset: UInt16 = 0,
    orderBy: UInt8 = 0,
    pubkeyPrefixLength: UInt8 = 4,
    priority: MeshRequestPriority
  ) async throws -> NeighboursResponse {
    try requireFullPublicKey(publicKey, operation: "requestNeighbours")
    var payload = Data()
    payload.append(0)
    payload.append(count)
    payload.append(contentsOf: withUnsafeBytes(of: offset.littleEndian) { Array($0) })
    payload.append(orderBy)
    payload.append(pubkeyPrefixLength)
    let randomTag = UInt32.random(in: 1...UInt32.max)
    payload.append(contentsOf: withUnsafeBytes(of: randomTag.littleEndian) { Array($0) })

    let publicKeyPrefix = Data(publicKey.prefix(6))
    let prefixLength = Int(pubkeyPrefixLength)
    let spec = MeshExchangeSpec<NeighboursResponse>(
      request: PacketBuilder.binaryRequest(to: publicKey, type: .neighbours, payload: payload),
      publicKey: publicKey,
      kind: .binary,
      operation: "Neighbours",
      acceptsBinaryResponse: true,
      routedEventFilter: { _ in false },
      matchRoutedEvent: nil,
      parseBinaryResponse: { payload, tag in
        NeighboursParser.parse(
          payload,
          publicKeyPrefix: publicKeyPrefix,
          tag: tag,
          prefixLength: prefixLength
        )
      },
      budget: { [overall = configuration.binaryRequestOverallTimeout] _ in overall },
      onTimeoutKnown: nil,
      loginPasswordDigest: nil,
      samePasswordExemption: false,
      onUnconsumedExit: nil
    )
    return try await performMeshExchange(spec, priority: priority)
  }

  /// Fetches all neighbors from a remote node with automatic pagination.
  func fetchAllNeighbours(
    from publicKey: Data,
    orderBy: UInt8 = 0,
    pubkeyPrefixLength: UInt8 = 4,
    priority: MeshRequestPriority
  ) async throws -> NeighboursResponse {
    try await NeighboursResponse.collectingAllPages { offset in
      if offset > 0 { try await clock.sleep(for: NeighboursResponse.interPageDelay) }
      return try await requestNeighbours(
        from: publicKey,
        count: 255,
        offset: offset,
        orderBy: orderBy,
        pubkeyPrefixLength: pubkeyPrefixLength,
        priority: priority
      )
    }
  }
}
