import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("SettingsService location and device GPS")
struct SettingsServiceLocationTests {
  @Test
  @MainActor
  func `getDeviceGPSState returns unsupported when gps custom var is missing`() async throws {
    let (service, session, transport) = try await makeService()
    defer { Task { await session.stop() } }

    let stateTask = Task { try await service.getDeviceGPSState() }
    try await waitUntil("service should request custom vars") {
      await transport.sentData.count == 2
    }

    await transport.simulateReceive(makeCustomVarsPacket())
    let state = try await stateTask.value

    #expect(state == DeviceGPSState(isSupported: false, isEnabled: false))
  }

  @Test
  @MainActor
  func `getDeviceGPSState returns enabled when gps custom var is on`() async throws {
    let (service, session, transport) = try await makeService()
    defer { Task { await session.stop() } }

    let stateTask = Task { try await service.getDeviceGPSState() }
    try await waitUntil("service should request custom vars") {
      await transport.sentData.count == 2
    }

    await transport.simulateReceive(makeCustomVarsPacket("gps:1,foo:bar"))
    let state = try await stateTask.value

    #expect(state == DeviceGPSState(isSupported: true, isEnabled: true))
  }

  @Test
  @MainActor
  func `setDeviceGPSEnabledVerified writes, verifies, and refreshes device info`() async throws {
    let (service, session, transport) = try await makeService(initialLatitude: 47.491031, initialLongitude: -120.339279)
    defer { Task { await session.stop() } }

    let stateTask = Task { try await service.setDeviceGPSEnabledVerified(false) }

    try await waitUntil("service should send device GPS update") {
      await transport.sentData.count == 2
    }
    let sentAfterWrite = await transport.sentData
    #expect(sentAfterWrite[1] == PacketBuilder.setCustomVar(key: "gps", value: "0"))
    await transport.simulateOK()

    try await waitUntil("service should verify device GPS state") {
      await transport.sentData.count == 3
    }
    let sentAfterVerify = await transport.sentData
    #expect(sentAfterVerify[2] == PacketBuilder.getCustomVars())
    await transport.simulateReceive(makeCustomVarsPacket("gps:0"))

    try await waitUntil("service should refresh self info") {
      await transport.sentData.count == 4
    }
    let sentAfterRefresh = await transport.sentData
    #expect(sentAfterRefresh[3] == PacketBuilder.appStart(clientId: SessionConfiguration.default.clientIdentifier))
    await transport.simulateReceive(makeSelfInfoPacket(latitude: 47.491031, longitude: -120.339279))

    let state = try await stateTask.value
    #expect(state == DeviceGPSState(isSupported: true, isEnabled: false))
  }

  @Test
  @MainActor
  func `setManualLocationVerified disables device GPS before writing location`() async throws {
    let (service, session, transport) = try await makeService(initialLatitude: 47.491031, initialLongitude: -120.339279)
    defer { Task { await session.stop() } }

    let saveTask = Task {
      try await service.setManualLocationVerified(latitude: 0, longitude: 0)
    }

    try await waitUntil("manual save should query device GPS state") {
      await transport.sentData.count == 2
    }
    await transport.simulateReceive(makeCustomVarsPacket("gps:1"))

    try await waitUntil("manual save should disable device GPS") {
      await transport.sentData.count == 3
    }
    let sentAfterDisable = await transport.sentData
    #expect(sentAfterDisable[2] == PacketBuilder.setCustomVar(key: "gps", value: "0"))
    await transport.simulateOK()

    try await waitUntil("manual save should verify device GPS off") {
      await transport.sentData.count == 4
    }
    await transport.simulateReceive(makeCustomVarsPacket("gps:0"))

    try await waitUntil("manual save should refresh after device GPS change") {
      await transport.sentData.count == 5
    }
    await transport.simulateReceive(makeSelfInfoPacket(latitude: 47.491031, longitude: -120.339279))

    try await waitUntil("manual save should send location update") {
      await transport.sentData.count == 6
    }
    let sentAfterLocationWrite = await transport.sentData
    #expect(sentAfterLocationWrite[5] == PacketBuilder.setCoordinates(latitude: 0, longitude: 0))
    await transport.simulateOK()

    try await waitUntil("manual save should verify location through self info") {
      await transport.sentData.count == 7
    }
    await transport.simulateReceive(makeSelfInfoPacket(latitude: 0, longitude: 0))

    let selfInfo = try await saveTask.value
    #expect(selfInfo.latitude == 0)
    #expect(selfInfo.longitude == 0)
  }

  @Test
  @MainActor
  func `setManualLocationVerified aborts when device GPS stays on`() async throws {
    let (service, session, transport) = try await makeService(initialLatitude: 47.491031, initialLongitude: -120.339279)
    defer { Task { await session.stop() } }

    let saveTask = Task {
      try await service.setManualLocationVerified(latitude: 0, longitude: 0)
    }

    try await waitUntil("manual save should query device GPS state") {
      await transport.sentData.count == 2
    }
    await transport.simulateReceive(makeCustomVarsPacket("gps:1"))

    try await waitUntil("manual save should disable device GPS") {
      await transport.sentData.count == 3
    }
    await transport.simulateOK()

    try await waitUntil("manual save should verify device GPS off") {
      await transport.sentData.count == 4
    }
    await transport.simulateReceive(makeCustomVarsPacket("gps:1"))

    await #expect(throws: SettingsServiceError.self) {
      _ = try await saveTask.value
    }

    let sent = await transport.sentData
    #expect(sent.count == 4)
  }

  private func makeService(
    initialLatitude: Double = 0,
    initialLongitude: Double = 0
  ) async throws -> (SettingsService, MeshCoreSession, MockTransport) {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)

    let startTask = Task {
      try await session.start()
    }

    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }

    await transport.simulateReceive(
      makeSelfInfoPacket(latitude: initialLatitude, longitude: initialLongitude)
    )
    try await startTask.value

    return (SettingsService(session: session), session, transport)
  }

  private func makeCustomVarsPacket(_ raw: String = "") -> Data {
    var packet = Data([ResponseCode.customVars.rawValue])
    packet.append(contentsOf: raw.utf8)
    return packet
  }

  private func makeSelfInfoPacket(
    latitude: Double,
    longitude: Double,
    advertisementLocationPolicy: UInt8 = 0
  ) -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(22)
    payload.append(22)
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(int32Bytes(latitude * 1_000_000))
    payload.append(int32Bytes(longitude * 1_000_000))
    payload.append(0)
    payload.append(advertisementLocationPolicy)
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
