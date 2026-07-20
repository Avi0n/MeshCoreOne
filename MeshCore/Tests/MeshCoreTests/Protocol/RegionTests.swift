import CryptoKit
import Foundation
@testable import MeshCore
import Testing

// MARK: - FloodScope.region

@Suite("FloodScope.region key derivation")
struct FloodScopeRegionTests {
  @Test
  func `region key matches SHA256 of #-prefixed name`() {
    let key = FloodScope.region("Europe").scopeKey()

    let expected = Data(SHA256.hash(data: Data("#Europe".utf8)).prefix(16))
    #expect(key == expected)
  }

  @Test
  func `region handles explicit # prefix idempotently`() {
    let withoutHash = FloodScope.region("Europe").scopeKey()
    let withHash = FloodScope.region("#Europe").scopeKey()

    #expect(withoutHash == withHash)
  }

  @Test
  func `region differs from channelName for the same string`() {
    let regionKey = FloodScope.region("Europe").scopeKey()
    let channelKey = FloodScope.channelName("Europe").scopeKey()

    #expect(regionKey != channelKey)
  }

  @Test
  func `disabled still produces 16 zero bytes`() {
    let key = FloodScope.disabled.scopeKey()

    #expect(key == Data(repeating: 0, count: 16))
    #expect(key.count == 16)
  }
}

// MARK: - PacketBuilder.sendAnonReq

@Suite("PacketBuilder.sendAnonReq wire format")
struct SendAnonReqTests {
  @Test
  func `regions request with path`() {
    let pubkey = Data(repeating: 0xAA, count: 32)
    let path = Data([0x11, 0x22])
    let pathLength: UInt8 = 0x41 // 2-byte hashes, 1 hop

    let packet = PacketBuilder.sendAnonReq(
      to: pubkey,
      type: .regions,
      pathLength: pathLength,
      path: path
    )

    #expect(packet[0] == 0x39, "Command code")
    #expect(Data(packet[1..<33]) == pubkey, "Public key")
    #expect(packet[33] == 0x01, "Request type (regions)")
    #expect(packet[34] == 0x41, "Path length byte")
    #expect(Data(packet[35..<37]) == Data([0x22, 0x11]), "Reversed path")
    #expect(packet.count == 37, "Total packet size")
  }

  @Test
  func `regions request zero-hop (no path)`() {
    let pubkey = Data(repeating: 0xBB, count: 32)

    let packet = PacketBuilder.sendAnonReq(
      to: pubkey,
      type: .regions,
      pathLength: 0x00,
      path: Data()
    )

    #expect(packet[0] == 0x39, "Command code")
    #expect(packet[33] == 0x01, "Request type (regions)")
    #expect(packet[34] == 0x00, "Zero path length")
    #expect(packet.count == 35, "No path bytes")
  }

  @Test
  func `pubkey longer than 32 bytes is truncated`() {
    let longPubkey = Data(repeating: 0xCC, count: 64)

    let packet = PacketBuilder.sendAnonReq(
      to: longPubkey,
      type: .regions,
      pathLength: 0x00,
      path: Data()
    )

    #expect(Data(packet[1..<33]) == Data(repeating: 0xCC, count: 32))
    #expect(packet.count == 35, "Truncated to 32-byte pubkey")
  }
}

// MARK: - RegionsParser

@Suite("RegionsParser")
struct RegionsParserTests {
  /// Builds a mock region response: [4-byte timestamp][UTF-8 string]
  private func makeResponse(_ regionString: String, timestamp: UInt32 = 0x1234_5678) -> Data {
    var data = Data()
    data.append(contentsOf: withUnsafeBytes(of: timestamp.littleEndian) { Array($0) })
    data.append(Data(regionString.utf8))
    return data
  }

  @Test
  func `parses comma-separated regions`() throws {
    let result = try RegionsParser.parse(makeResponse("Europe,UK,France"))

    #expect(result == ["Europe", "UK", "France"])
  }

  @Test
  func `parses single region`() throws {
    let result = try RegionsParser.parse(makeResponse("Europe"))

    #expect(result == ["Europe"])
  }

  @Test
  func `parses empty string to empty array`() throws {
    let result = try RegionsParser.parse(makeResponse(""))

    #expect(result == [])
  }

  @Test
  func `strips null terminators`() throws {
    let result = try RegionsParser.parse(makeResponse("Europe,UK\0\0"))

    #expect(result == ["Europe", "UK"])
  }

  @Test
  func `throws on response shorter than 4 bytes`() {
    #expect(throws: MeshCoreError.self) {
      _ = try RegionsParser.parse(Data([0x01, 0x02]))
    }
  }

  @Test
  func `throws on invalid UTF-8`() {
    var data = Data(repeating: 0, count: 4) // timestamp
    data.append(contentsOf: [0xFF, 0xFE]) // invalid UTF-8
    #expect(throws: MeshCoreError.self) {
      _ = try RegionsParser.parse(data)
    }
  }

  @Test
  func `filters out wildcard region`() throws {
    let result = try RegionsParser.parse(makeResponse("*,Europe,UK"))

    #expect(result == ["Europe", "UK"])
  }

  @Test
  func `filters out wildcard-only response to empty array`() throws {
    let result = try RegionsParser.parse(makeResponse("*"))

    #expect(result == [])
  }

  @Test
  func `filters out whitespace-only entries`() throws {
    let result = try RegionsParser.parse(makeResponse("Europe, ,UK"))

    #expect(result == ["Europe", "UK"])
  }

  @Test
  func `trims whitespace around region names`() throws {
    let result = try RegionsParser.parse(makeResponse(" Europe , UK "))

    #expect(result == ["Europe", "UK"])
  }
}

// MARK: - requestRegions Integration

@Suite("requestRegions integration")
struct RequestRegionsIntegrationTests {
  /// Builds a selfInfo packet to complete session.start().
  /// Format: [0x01][advType:1][txPower:1][maxTxPower:1][pubkey:32][lat:4LE][lon:4LE]
  ///         [flags:1][reserved:1][reserved:1][reserved:1][freq:4LE][bw:4LE][sf:1][cr:1][name:UTF8]
  private func makeSelfInfoPacket() -> Data {
    var data = Data([ResponseCode.selfInfo.rawValue])
    data.append(0) // advType
    data.append(0) // txPower
    data.append(0) // maxTxPower
    data.append(Data(repeating: 0x01, count: 32)) // publicKey
    data.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lat
    data.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lon
    data.append(0) // flags
    data.append(0) // reserved
    data.append(0) // reserved
    data.append(0) // reserved
    data.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) }) // freq
    data.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) }) // bw
    data.append(7) // sf
    data.append(5) // cr
    data.append(contentsOf: "Test".utf8) // name
    return data
  }

  /// Starts a session by driving the appStart → selfInfo handshake.
  private func startSession(_ session: MeshCoreSession, transport: MockTransport) async throws {
    let startTask = Task { try await session.start() }

    try await waitUntil("transport should have sent appStart") {
      await transport.sentData.count >= 1
    }

    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    await transport.clearSentData()
  }

  /// Builds a messageSent raw packet.
  /// Wire format: [0x06][type:1][expectedAck:4][suggestedTimeoutMs:4LE]
  private func makeMessageSentPacket(type: UInt8 = 0, expectedAck: Data, timeoutMs: UInt32 = 5000) -> Data {
    var data = Data([ResponseCode.messageSent.rawValue])
    data.append(type)
    data.append(expectedAck)
    data.append(contentsOf: withUnsafeBytes(of: timeoutMs.littleEndian) { Array($0) })
    return data
  }

  /// Builds a binaryResponse raw packet.
  /// Wire format: [0x8C][requestType:1][tag:4][responseData...]
  private func makeBinaryResponsePacket(tag: Data, regionString: String, repeaterTimestamp: UInt32 = 0xAABB_CCDD) -> Data {
    var data = Data([ResponseCode.binaryResponse.rawValue])
    data.append(0x00) // requestType (unused by parser)
    data.append(tag)
    // responseData: [repeater_timestamp:4LE][UTF-8 regions]
    data.append(contentsOf: withUnsafeBytes(of: repeaterTimestamp.littleEndian) { Array($0) })
    data.append(Data(regionString.utf8))
    return data
  }

  /// Waits for the next command to be sent, then responds with OK.
  private func acknowledgeNextCommand(_ transport: MockTransport, sentCountBefore: Int, label: String = "command") async throws {
    try await waitUntil("transport should have sent \(label)") {
      await transport.sentData.count > sentCountBefore
    }
    await transport.simulateOK()
  }

  private func makeTestContact(outPathLength: UInt8 = 0xFF, outPath: Data = Data()) -> MeshContact {
    let publicKey = Data(repeating: 0xDD, count: 32)
    return MeshContact(
      id: publicKey.hexString,
      publicKey: publicKey,
      type: .repeater,
      flags: [],
      outPathLength: outPathLength,
      outPath: outPath,
      advertisedName: "TestRepeater",
      lastAdvertisement: Date(),
      latitude: 0,
      longitude: 0,
      lastModified: Date()
    )
  }

  @Test
  func `full two-phase flow returns parsed regions`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "Test")
    )
    try await startSession(session, transport: transport)

    let contact = makeTestContact()
    let expectedAck = Data([0x01, 0x02, 0x03, 0x04])

    let regionsTask = Task {
      try await session.requestRegions(from: contact)
    }

    // Acknowledge updateContact (sets zero-hop for flood-routed contact)
    try await acknowledgeNextCommand(transport, sentCountBefore: 0, label: "updateContact")

    try await waitUntil("transport should have sent anon request") {
      await transport.sentData.count >= 2
    }

    // Phase 1: firmware acknowledges the send
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: expectedAck))
    // Phase 2: repeater responds with region list
    await transport.simulateReceive(makeBinaryResponsePacket(tag: expectedAck, regionString: "Europe,UK,France"))

    // Acknowledge resetPath (restores flood routing)
    try await acknowledgeNextCommand(transport, sentCountBefore: 2, label: "resetPath")

    let result = try await regionsTask.value
    #expect(result == ["Europe", "UK", "France"])

    await session.stop()
  }

  @Test
  func `timeout when no binaryResponse arrives`() async throws {
    let transport = MockTransport()
    // Zero floor keeps the firmware-suggested timeout in charge so the test
    // observes the timeout without waiting out the flood-return floor.
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 10, clientIdentifier: "Test", binaryRequestMinimumTimeout: 0
      )
    )
    try await startSession(session, transport: transport)

    let contact = makeTestContact()
    let expectedAck = Data([0xDE, 0xAD, 0xBE, 0xEF])

    let regionsTask = Task {
      try await session.requestRegions(from: contact)
    }

    // Acknowledge updateContact for flood-routed contact
    try await acknowledgeNextCommand(transport, sentCountBefore: 0, label: "updateContact")

    try await waitUntil("transport should have sent anon request") {
      await transport.sentData.count >= 2
    }

    // Firmware acknowledges the send (unblocks timeout stream) but repeater never responds
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: expectedAck, timeoutMs: 100))

    // Acknowledge resetPath after timeout fires
    try await acknowledgeNextCommand(transport, sentCountBefore: 2, label: "resetPath")

    await #expect(throws: MeshCoreError.self) {
      _ = try await regionsTask.value
    }

    await session.stop()
  }

  @Test
  func `device error propagates correctly`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "Test")
    )
    try await startSession(session, transport: transport)

    let contact = makeTestContact()

    let regionsTask = Task {
      try await session.requestRegions(from: contact)
    }

    // Acknowledge updateContact for flood-routed contact
    try await acknowledgeNextCommand(transport, sentCountBefore: 0, label: "updateContact")

    try await waitUntil("transport should have sent anon request") {
      await transport.sentData.count >= 2
    }

    await transport.simulateError(code: 10)

    // Acknowledge resetPath after error
    try await acknowledgeNextCommand(transport, sentCountBefore: 2, label: "resetPath")

    do {
      _ = try await regionsTask.value
      Issue.record("Expected requestRegions to throw")
    } catch let error as MeshCoreError {
      guard case let .deviceError(code) = error else {
        Issue.record("Expected MeshCoreError.deviceError, got \(error)")
        return
      }
      #expect(code == 10)
    }

    await session.stop()
  }

  @Test
  func `temporarily sets zero-hop before sending for flood-routed contact`() async throws {
    let transport = MockTransport()
    // Zero floor: the test drives the exchange to a fast timeout to observe
    // the path restore.
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 10, clientIdentifier: "Test", binaryRequestMinimumTimeout: 0
      )
    )
    try await startSession(session, transport: transport)

    let contact = makeTestContact(outPathLength: 0xFF, outPath: Data())

    let regionsTask = Task {
      try await session.requestRegions(from: contact)
    }

    // Acknowledge updateContact for flood-routed contact
    try await acknowledgeNextCommand(transport, sentCountBefore: 0, label: "updateContact")

    try await waitUntil("transport should have sent anon request") {
      await transport.sentData.count >= 2
    }

    // Send messageSent to unblock timeout stream, then let it timeout (no binaryResponse)
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0xAA, 0xBB, 0xCC, 0xDD]), timeoutMs: 100))

    // Acknowledge resetPath after timeout
    try await acknowledgeNextCommand(transport, sentCountBefore: 2, label: "resetPath")

    // Let it timeout — we just want to inspect the sent packets
    _ = try? await regionsTask.value

    let sentData = await transport.sentData
    #expect(sentData.count >= 3, "Should have sent updateContact, sendAnonReq, and resetPath")

    // First packet: updateContact (0x09) setting outPathLength to 0 (zero-hop direct)
    let updatePacket = sentData[0]
    #expect(updatePacket[0] == CommandCode.updateContact.rawValue, "First command is updateContact")
    #expect(Data(updatePacket[1..<33]) == contact.publicKey, "updateContact public key")
    #expect(updatePacket[35] == 0x00, "outPathLength set to zero-hop")

    // Second packet: sendAnonReq (0x39) with zero-hop path
    let anonPacket = sentData[1]
    #expect(anonPacket[0] == CommandCode.sendAnonReq.rawValue, "Command code")
    #expect(Data(anonPacket[1..<33]) == contact.publicKey, "Public key")
    #expect(anonPacket[33] == AnonRequestType.regions.rawValue, "Request type")
    #expect(anonPacket[34] == 0x00, "Zero path length for flood-routed")
    #expect(anonPacket.count == 35, "No path bytes for flood-routed")

    // Third packet: resetPath (0x0D) to restore flood routing
    let resetPacket = sentData[2]
    #expect(resetPacket[0] == CommandCode.resetPath.rawValue, "Third command is resetPath")
    #expect(Data(resetPacket[1..<33]) == contact.publicKey, "resetPath public key")

    await session.stop()
  }

  @Test
  func `requestRegions preserves an unmodeled raw type byte in the temp write`() async throws {
    let transport = MockTransport()
    // Zero floor: the test drives the exchange to a fast timeout to observe
    // the temp contact write.
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 10, clientIdentifier: "Test", binaryRequestMinimumTimeout: 0
      )
    )
    try await startSession(session, transport: transport)

    // type falls back to .chat for the unmodeled 0x04 byte; typeRawValue carries the real byte.
    let contact = MeshContact(
      id: "r", publicKey: Data(repeating: 0xCD, count: 32), type: .chat,
      typeRawValue: 0x04, flags: ContactFlags(rawValue: 0), outPathLength: 0xFF,
      outPath: Data(), advertisedName: "Repeater", lastAdvertisement: Date(timeIntervalSince1970: 1000),
      latitude: 0, longitude: 0, lastModified: Date()
    )

    let regionsTask = Task { try await session.requestRegions(from: contact) }

    // Drive the proven flood-route flow: ack the zero-hop temp write, send a messageSent to
    // start (and quickly expire) the region timeout, then ack the resetPath restore.
    try await acknowledgeNextCommand(transport, sentCountBefore: 0, label: "temp write")
    try await waitUntil("transport should have sent anon request") {
      await transport.sentData.count >= 2
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0xAA, 0xBB, 0xCC, 0xDD]), timeoutMs: 100))
    try await acknowledgeNextCommand(transport, sentCountBefore: 2, label: "resetPath")
    _ = try? await regionsTask.value

    // First sent packet is the zero-hop temp write; its type byte must be the raw 0x04.
    let tempFrame = await transport.sentData[0]
    #expect(tempFrame[0] == CommandCode.updateContact.rawValue) // 0x09
    #expect(tempFrame[33] == 0x04, "raw type byte must survive, not coerce to 0x01")
    #expect(tempFrame[35] == 0x00, "zero-hop direct out_path_len")

    await session.stop()
  }
}
