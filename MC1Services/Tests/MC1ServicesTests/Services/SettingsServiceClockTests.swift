import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("SettingsService clock")
struct SettingsServiceClockTests {
  @Test
  @MainActor
  func `getTime reads back the device clock`() async throws {
    let (service, session, transport) = try await makeService()
    defer { Task { await session.stop() } }

    let expected = Date(timeIntervalSince1970: 1_700_000_000)
    let readTask = Task { try await service.getTime() }

    try await waitUntil("service should request the device time") {
      await transport.sentData.count == 2
    }
    let sent = await transport.sentData
    #expect(sent[1] == PacketBuilder.getTime())

    await transport.simulateReceive(makeCurrentTimePacket(expected))
    let time = try await readTask.value

    #expect(time == expected)
  }

  @Test
  @MainActor
  func `setTime writes the device clock`() async throws {
    let (service, session, transport) = try await makeService()
    defer { Task { await session.stop() } }

    let target = Date(timeIntervalSince1970: 1_700_000_000)
    let writeTask = Task { try await service.setTime(target) }

    try await waitUntil("service should send the device time") {
      await transport.sentData.count == 2
    }
    let sent = await transport.sentData
    #expect(sent[1] == PacketBuilder.setTime(target))

    await transport.simulateOK()
    try await writeTask.value
  }

  private func makeService() async throws -> (SettingsService, MeshCoreSession, MockTransport) {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)

    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value

    return (SettingsService(session: session), session, transport)
  }

  private func makeCurrentTimePacket(_ date: Date) -> Data {
    var packet = Data([ResponseCode.currentTime.rawValue])
    let seconds = UInt32(date.timeIntervalSince1970)
    packet.append(contentsOf: withUnsafeBytes(of: seconds.littleEndian) { Array($0) })
    return packet
  }

  private func makeSelfInfoPacket() -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(22)
    payload.append(22)
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
    payload.append(withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(withUnsafeBytes(of: UInt32(915_000).littleEndian) { Data($0) })
    payload.append(withUnsafeBytes(of: UInt32(125_000).littleEndian) { Data($0) })
    payload.append(7)
    payload.append(5)
    payload.append(contentsOf: "Test".utf8)

    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return packet
  }
}
