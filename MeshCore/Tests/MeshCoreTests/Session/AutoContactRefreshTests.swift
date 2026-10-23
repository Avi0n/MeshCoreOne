import Foundation
@testable import MeshCore
import Testing

@Suite("MeshCoreSession auto contact refresh", .timeLimit(.minutes(1)))
struct AutoContactRefreshTests {
  @Test
  func `auto-refresh coalesces bursty contact invalidations`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MeshCore-Tests")
    )
    try await startSession(session, transport: transport)
    await session.setAutoUpdateContacts(true)

    do {
      let finalAdvertKey = Data(repeating: 0x33, count: 32)
      let finalAdvertEvents = await session.events(filter: .advertisement(fromPrefix: finalAdvertKey))
      var finalAdvertIterator = finalAdvertEvents.makeAsyncIterator()
      let advertisementPacket = makeAdvertisementPacket(publicKey: Data(repeating: 0x22, count: 32))
      await transport.simulateReceive(advertisementPacket)
      try await waitUntil("first contact refresh should be sent") {
        await transport.sentData.count == 1
      }
      #expect(await transport.sentData.first == PacketBuilder.getContacts())

      await transport.simulateReceive(advertisementPacket)
      await transport.simulateReceive(makeAdvertisementPacket(publicKey: finalAdvertKey))
      // Scheduling precedes dispatch, so the last advert proves all invalidations were admitted.
      try #require(await finalAdvertIterator.next() != nil)
      #expect(await session.autoContactRefreshStateForTest.requested)
      #expect(await transport.sentData.count == 1)
      let refreshTask = try #require(await session.autoContactRefreshStateForTest.task)

      await simulateEmptyContactsResponse(transport, lastModified: 1)
      try await waitUntil("the pending invalidation should produce one follow-up refresh") {
        await transport.sentData.count == 2
      }
      await simulateEmptyContactsResponse(transport, lastModified: 2)
      await refreshTask.value
      #expect(await transport.sentData.count == 2)
    } catch {
      await session.stop()
      throw error
    }
    await session.stop()
  }

  private func simulateEmptyContactsResponse(_ transport: MockTransport, lastModified: UInt32) async {
    await transport.simulateReceive(makeContactsStartPacket(count: 0))
    await transport.simulateReceive(makeContactsEndPacket(lastModified: lastModified))
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

  private func makeAdvertisementPacket(publicKey: Data) -> Data {
    var packet = Data([ResponseCode.advertisement.rawValue])
    packet.append(publicKey)
    return packet
  }

  private func makeContactsStartPacket(count: UInt32) -> Data {
    var packet = Data([ResponseCode.contactStart.rawValue])
    packet.append(contentsOf: withUnsafeBytes(of: count.littleEndian) { Array($0) })
    return packet
  }

  private func makeContactsEndPacket(lastModified: UInt32) -> Data {
    var packet = Data([ResponseCode.contactEnd.rawValue])
    packet.append(contentsOf: withUnsafeBytes(of: lastModified.littleEndian) { Array($0) })
    return packet
  }

  private func makeSelfInfoPacket() -> Data {
    var payload = Data([ResponseCode.selfInfo.rawValue])
    payload.append(0x01)
    payload.append(UInt8(bitPattern: Int8(20)))
    payload.append(UInt8(bitPattern: Int8(22)))
    payload.append(Data(repeating: 0xAA, count: 32))
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
    payload.append(0x00)
    payload.append(0x00)
    payload.append(0x00)
    payload.append(0x01)
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(910_525).littleEndian) { Data($0) })
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(62500).littleEndian) { Data($0) })
    payload.append(0x07)
    payload.append(0x05)
    payload.append("TestNode".data(using: .utf8)!)
    return payload
  }
}
