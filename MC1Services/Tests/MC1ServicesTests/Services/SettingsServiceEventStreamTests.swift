import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("SettingsService event stream")
struct SettingsServiceEventStreamTests {
  private actor EventCollector {
    var events: [SettingsEvent] = []
    func record(_ event: SettingsEvent) {
      events.append(event)
    }

    var count: Int {
      events.count
    }
  }

  @Test
  @MainActor
  func `replacing the event subscriber keeps the replacement connected`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    defer { Task { await session.stop() } }

    let service = SettingsService(session: session)

    let streamA = await service.events()
    let streamB = await service.events()

    // The second subscription finished A; drain it so its termination
    // callback has run (it must not disconnect B).
    for await _ in streamA {}

    let collector = EventCollector()
    let collectTask = Task {
      for await event in streamB {
        await collector.record(event)
      }
    }

    let refreshTask = Task { try await service.refreshDeviceInfo() }
    try await waitUntil("service should re-request self info") {
      await transport.sentData.count == 2
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await refreshTask.value

    try await waitUntil("the replacement subscriber must receive the deviceUpdated event") {
      await collector.count > 0
    }
    collectTask.cancel()
  }

  private func makeSelfInfoPacket() -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(22)
    payload.append(22)
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(int32Bytes(0))
    payload.append(int32Bytes(0))
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(uint32Bytes(915_000))
    payload.append(uint32Bytes(125_000))
    payload.append(7)
    payload.append(5)
    payload.append(contentsOf: "Test".utf8)

    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return packet
  }

  private func int32Bytes(_ value: Double) -> Data {
    withUnsafeBytes(of: Int32(value.rounded()).littleEndian) { Data($0) }
  }

  private func uint32Bytes(_ value: UInt32) -> Data {
    withUnsafeBytes(of: value.littleEndian) { Data($0) }
  }
}
