import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("SettingsService default flood scope")
struct SettingsServiceDefaultFloodScopeTests {
  @Test
  @MainActor
  func `setDefaultFloodScopeVerified truncates overlong names before send and verify`() async throws {
    let maxBytes = ProtocolLimits.maxDefaultFloodScopeNameBytes
    let overlong = String(repeating: "a", count: maxBytes + 5)
    let truncated = String(repeating: "a", count: maxBytes)

    let (service, session, transport) = try await makeService()
    defer { Task { await session.stop() } }

    let setTask = Task { try await service.setDefaultFloodScopeVerified(name: overlong) }

    try await waitUntil("service should send setDefaultFloodScope command") {
      await transport.sentData.count == 2
    }
    let sentAfterWrite = await transport.sentData
    let expectedPacket = PacketBuilder.setDefaultFloodScope(
      name: truncated,
      scope: .region(truncated)
    )
    #expect(sentAfterWrite[1] == expectedPacket)
    await transport.simulateOK()

    try await waitUntil("service should verify via getDefaultFloodScope") {
      await transport.sentData.count == 3
    }
    let sentAfterVerify = await transport.sentData
    #expect(sentAfterVerify[2] == PacketBuilder.getDefaultFloodScope())
    await transport.simulateReceive(makeDefaultFloodScopePacket(name: truncated))

    let result = try await setTask.value
    #expect(result == truncated)
  }

  @Test
  @MainActor
  func `setDefaultFloodScopeVerified forwards names at or below the cap unchanged`() async throws {
    let name = "Germany"

    let (service, session, transport) = try await makeService()
    defer { Task { await session.stop() } }

    let setTask = Task { try await service.setDefaultFloodScopeVerified(name: name) }

    try await waitUntil("service should send setDefaultFloodScope command") {
      await transport.sentData.count == 2
    }
    let sent = await transport.sentData
    #expect(sent[1] == PacketBuilder.setDefaultFloodScope(name: name, scope: .region(name)))
    await transport.simulateOK()

    try await waitUntil("service should verify via getDefaultFloodScope") {
      await transport.sentData.count == 3
    }
    await transport.simulateReceive(makeDefaultFloodScopePacket(name: name))

    let result = try await setTask.value
    #expect(result == name)
  }

  @Test
  @MainActor
  func `setDefaultFloodScopeVerified clears the scope when name is nil`() async throws {
    let (service, session, transport) = try await makeService()
    defer { Task { await session.stop() } }

    let setTask = Task { try await service.setDefaultFloodScopeVerified(name: nil) }

    try await waitUntil("service should send setDefaultFloodScope clear command") {
      await transport.sentData.count == 2
    }
    let sent = await transport.sentData
    #expect(sent[1] == PacketBuilder.setDefaultFloodScope(name: "", scope: .disabled))
    await transport.simulateOK()

    try await waitUntil("service should verify via getDefaultFloodScope") {
      await transport.sentData.count == 3
    }
    await transport.simulateReceive(Data([ResponseCode.defaultFloodScope.rawValue]))

    let result = try await setTask.value
    #expect(result == nil)
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

  private func makeDefaultFloodScopePacket(name: String) -> Data {
    var packet = Data([ResponseCode.defaultFloodScope.rawValue])
    var nameField = Data(name.utf8)
    while nameField.count < 31 {
      nameField.append(0)
    }
    packet.append(nameField)
    packet.append(Data(repeating: 0, count: 16))
    return packet
  }

  private func int32Bytes(_ value: Double) -> Data {
    withUnsafeBytes(of: Int32(value.rounded()).littleEndian) { Data($0) }
  }

  private func uint32Bytes(_ value: UInt32) -> Data {
    withUnsafeBytes(of: value.littleEndian) { Data($0) }
  }
}
