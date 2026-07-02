import Foundation
@testable import MeshCore
import Testing

@Suite("MeshCoreSession auto message fetch")
struct AutoMessageFetchTests {
  @Test
  func `concurrent getMessage calls share one wire request`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "MeshCore-Tests")
    )
    try await startSession(session, transport: transport)

    let firstTask = Task { try await session.getMessage(timeout: 0.2) }
    let secondTask = Task { try await session.getMessage(timeout: 0.2) }

    try await waitUntil("getMessage command should be sent once") {
      await transport.sentData.count == 1
    }

    await transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))

    let first = try await firstTask.value
    let second = try await secondTask.value

    assertNoMoreMessages(first)
    assertNoMoreMessages(second)
    #expect(await transport.sentData.count == 1)
  }

  @Test
  func `auto-fetch coalesces repeated messagesWaiting notifications`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "MeshCore-Tests")
    )
    try await startSession(session, transport: transport)
    await session.startAutoMessageFetching()

    let waitingPacket = Data([ResponseCode.messagesWaiting.rawValue])
    await transport.simulateReceive(waitingPacket)
    await transport.simulateReceive(waitingPacket)
    await transport.simulateReceive(waitingPacket)

    try await waitUntil("first drain poll should be sent") {
      await transport.sentData.count == 1
    }

    await transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))
    try? await Task.sleep(for: .milliseconds(50))

    let sentCount = await transport.sentData.count
    #expect(sentCount >= 1)
    #expect(sentCount <= 2)

    if sentCount == 2 {
      await transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))
      try? await Task.sleep(for: .milliseconds(20))
      #expect(await transport.sentData.count == 2)
    }

    await session.stopAutoMessageFetching()
  }

  @Test
  func `manual getMessage shares in-flight auto-fetch poll`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "MeshCore-Tests")
    )
    try await startSession(session, transport: transport)
    await session.startAutoMessageFetching()

    await transport.simulateReceive(Data([ResponseCode.messagesWaiting.rawValue]))

    try await waitUntil("auto-fetch should issue the first poll") {
      await transport.sentData.count == 1
    }

    let manualPoll = Task { try await session.getMessage(timeout: 0.2) }
    try? await Task.sleep(for: .milliseconds(20))

    #expect(await transport.sentData.count == 1)

    await transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))

    let result = try await manualPoll.value
    assertNoMoreMessages(result)
    #expect(await transport.sentData.count == 1)
    await session.stopAutoMessageFetching()
  }

  private func startSession(_ session: MeshCoreSession, transport: MockTransport) async throws {
    let startTask = Task { try await session.start() }

    try await waitUntil("appStart command should be sent") {
      await transport.sentData.count >= 1
    }

    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    await transport.clearSentData()
  }

  private func waitUntil(
    _ description: String,
    timeout: Duration = .seconds(10),
    pollInterval: Duration = .milliseconds(10),
    condition: @escaping @Sendable () async -> Bool
  ) async throws {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: timeout)

    while clock.now < deadline {
      if await condition() { return }
      try await Task.sleep(for: pollInterval)
    }

    Issue.record("Timed out waiting: \(description)")
    throw MeshCoreError.timeout
  }

  private func makeSelfInfoPacket() -> Data {
    var payload = Data([ResponseCode.selfInfo.rawValue])
    payload.append(0x01) // advType
    payload.append(UInt8(bitPattern: Int8(20))) // txPower
    payload.append(UInt8(bitPattern: Int8(22))) // maxTxPower
    payload.append(Data(repeating: 0xAA, count: 32)) // publicKey
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) }) // lat
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) }) // lon
    payload.append(0x00) // multiAcks
    payload.append(0x00) // adv policy
    payload.append(0x00) // telemetry mode
    payload.append(0x01) // manual add
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(910_525).littleEndian) { Data($0) }) // freq
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(62500).littleEndian) { Data($0) }) // bw
    payload.append(0x07) // sf
    payload.append(0x05) // cr
    payload.append("TestNode".data(using: .utf8)!)
    return payload
  }

  private func assertNoMoreMessages(_ result: MessageResult) {
    guard case .noMoreMessages = result else {
      Issue.record("Expected .noMoreMessages, got \(result)")
      return
    }
  }
}
