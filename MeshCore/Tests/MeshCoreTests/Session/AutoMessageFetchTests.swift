import Foundation
@testable import MeshCore
import Testing

@Suite("MeshCoreSession auto message fetch", .timeLimit(.minutes(1)))
struct AutoMessageFetchTests {
  @Test
  func `auto-fetch coalesces repeated messagesWaiting notifications`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MeshCore-Tests")
    )
    try await startSession(session, transport: transport)
    await session.startAutoMessageFetching()

    do {
      let waitingPacket = Data([ResponseCode.messagesWaiting.rawValue])
      await transport.simulateReceive(waitingPacket)
      try await waitUntil("first drain poll should be sent") {
        await transport.sentData.count == 1
      }

      await transport.simulateReceive(waitingPacket)
      await transport.simulateReceive(waitingPacket)
      try await waitUntil("the auto-fetch loop should consume all three notifications") {
        await session.processedMessagesWaitingCountForTest == 3
      }
      #expect(await session.autoMessageDrainStateForTest.requested)
      #expect(await transport.sentData.count == 1)
      let drainTask = try #require(await session.autoMessageDrainStateForTest.task)

      await transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))
      try await waitUntil("the pending notification should produce one follow-up poll") {
        await transport.sentData.count == 2
      }
      await transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))
      await drainTask.value
      #expect(await transport.sentData.count == 2)
    } catch {
      await session.stop()
      throw error
    }

    await session.stop()
  }

  @Test
  func `manual getMessage shares in-flight auto-fetch poll`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MeshCore-Tests")
    )
    try await startSession(session, transport: transport)
    await session.startAutoMessageFetching()

    await transport.simulateReceive(Data([ResponseCode.messagesWaiting.rawValue]))

    try await waitUntil("auto-fetch should issue the first poll") {
      await transport.sentData.count == 1
    }

    let manualPoll = Task { try await session.getMessage(timeout: 10) }
    try await waitUntil("manual poll should join the active auto-fetch") {
      await session.getMessageCallerCountForTest == 2
    }

    #expect(await transport.sentData.count == 1)

    await transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))

    let result = try await manualPoll.value
    assertNoMoreMessages(result)
    #expect(await transport.sentData.count == 1)
    await session.stop()
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
