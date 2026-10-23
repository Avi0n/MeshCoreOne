import Foundation
@testable import MeshCore
import Testing

@Suite("MMA requests")
struct MMARequestTests {
  @Test
  func `requests relative seconds and parses entries after the server clock`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    let publicKey = Data(repeating: 0x42, count: 32)
    let before = Date()
    let start = before.addingTimeInterval(-3600)
    let end = before.addingTimeInterval(-60)
    let request = Task { try await session.requestMMA(from: publicKey, start: start, end: end) }
    let sent = try await nextCommand(transport)
    let after = Date()

    #expect(sent.prefix(34) == Data([CommandCode.binaryRequest.rawValue]) + publicKey + Data([BinaryRequestType.mma.rawValue]))
    #expect(sent.count == 44)
    #expect(sent.readUInt32LE(at: 34) >= UInt32(before.timeIntervalSince(start)))
    #expect(sent.readUInt32LE(at: 34) <= UInt32(after.timeIntervalSince(start)))
    #expect(sent.readUInt32LE(at: 38) >= UInt32(before.timeIntervalSince(end)))
    #expect(sent.readUInt32LE(at: 38) <= UInt32(after.timeIntervalSince(end)))
    #expect(sent.suffix(2) == Data([0, 0]))

    let tag = Data([0x11, 0x22, 0x33, 0x44])
    await sendResponse(
      transport,
      tag: tag,
      body: Data([0x00, 0xF1, 0x53, 0x65, 0x01, 0x67, 0x00, 0xC8, 0x01, 0x2C, 0x00, 0xFA])
    )
    let response = try await request.value
    #expect(response.publicKeyPrefix == publicKey.prefix(6))
    #expect(response.tag == tag)
    #expect(response.data == [MMAEntry(channel: 1, type: "Temperature", min: 20, max: 30, avg: 25)])
    await session.stop()
  }

  @Test(arguments: [3, 4])
  func `requires a complete server clock even when no entries follow`(bodySize: Int) async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    let request = Task {
      try await session.requestMMA(from: Data(repeating: 0x42, count: 32), start: .distantPast, end: Date().addingTimeInterval(60))
    }
    let sent = try await nextCommand(transport)
    #expect(sent.readUInt32LE(at: 34) == UInt32.max)
    #expect(sent.readUInt32LE(at: 38) == 0)
    await sendResponse(transport, tag: Data([1, 2, 3, 4]), body: Data(repeating: 0, count: bodySize))

    if bodySize == 4 {
      #expect(try await request.value.data.isEmpty)
    } else {
      let error = await #expect(throws: MeshCoreError.self) { try await request.value }
      guard case .parseError? = error else {
        Issue.record("Expected parseError for a truncated server clock")
        await session.stop()
        return
      }
    }
    await session.stop()
  }

  private func sendResponse(_ transport: MockTransport, tag: Data, body: Data) async {
    var sent = Data([ResponseCode.messageSent.rawValue, 0])
    sent.append(tag)
    sent.appendLittleEndian(UInt32(1000))
    await transport.simulateReceive(sent)
    await transport.simulateReceive(Data([ResponseCode.binaryResponse.rawValue, 0]) + tag + body)
  }

  private func startedSession(_ transport: MockTransport) async throws -> MeshCoreSession {
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(binaryRequestOverallTimeout: 2, binaryRequestRetransmitInterval: nil)
    )
    let start = Task { try await session.start() }
    _ = try await nextCommand(transport)
    await transport.simulateReceive(Data([ResponseCode.selfInfo.rawValue, 1, 22, 22]) + Data(repeating: 0, count: 54))
    try await start.value
    await transport.clearSentData()
    return session
  }

  private func nextCommand(_ transport: MockTransport) async throws -> Data {
    let deadline = ContinuousClock.now.advanced(by: .seconds(2))
    while ContinuousClock.now < deadline {
      if let sent = await transport.sentData.last { return sent }
      try await Task.sleep(for: .milliseconds(5))
    }
    throw MeshCoreError.timeout
  }
}
