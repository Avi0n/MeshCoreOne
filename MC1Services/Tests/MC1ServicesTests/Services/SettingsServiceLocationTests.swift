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

  @Test
  @MainActor
  func `an axis edit with GPS on reads the sibling after GPS shuts down`() async throws {
    let radio = LocationRadio(latitude: 47.491031, longitude: -120.339279, gps: "1")
    await radio.setCoordinatesAfterGPSOff(longitude: -122)
    let (service, session) = try await makeLocationService(radio: radio)
    defer { Task { await session.stop() } }
    await radio.resetTrace()

    _ = try await service.setLatitudeVerified(37.7749)

    let trace = await radio.trace
    expectInOrder(trace, [
      "getCustomVars", "setCustomVar gps:0", "getCustomVars", "appStart", "appStart", "setCoordinates", "appStart",
    ])
    let write = try #require(await radio.coordinateWrites.last)
    #expect(abs(write.latitude - 37.7749) < 0.0001)
    #expect(abs(write.longitude - -122) < 0.0001)

    await radio.resetTrace()
    await radio.setCoordinatesAfterGPSOff(latitude: 20)
    await radio.setGPS("1")
    _ = try await service.setLongitudeVerified(-122.4194)
    await expectInOrder(radio.trace, [
      "getCustomVars", "setCustomVar gps:0", "getCustomVars", "appStart", "appStart", "setCoordinates", "appStart",
    ])
    let longitudeWrite = try #require(await radio.coordinateWrites.last)
    #expect(abs(longitudeWrite.latitude - 20) < 0.0001)
    #expect(abs(longitudeWrite.longitude - -122.4194) < 0.0001)
  }

  @Test
  @MainActor
  func `an axis edit skips GPS shutdown when GPS is off or unsupported`() async throws {
    let off = LocationRadio(latitude: 10, longitude: -20, gps: "0")
    let (offService, offSession) = try await makeLocationService(radio: off)
    defer { Task { await offSession.stop() } }
    await off.resetTrace()
    _ = try await offService.setLatitudeVerified(11)
    #expect(await off.trace == ["getCustomVars", "appStart", "setCoordinates", "appStart"])

    let unsupported = LocationRadio(latitude: 10, longitude: -20, gps: nil)
    let (unsupportedService, unsupportedSession) = try await makeLocationService(radio: unsupported)
    defer { Task { await unsupportedSession.stop() } }
    await unsupported.resetTrace()
    _ = try await unsupportedService.setLongitudeVerified(-21)
    #expect(await unsupported.trace == ["getCustomVars", "appStart", "setCoordinates", "appStart"])
    #expect(await unsupported.customVarWrites.isEmpty)
  }

  @Test
  @MainActor
  func `a rejected GPS shutdown sends no coordinate write`() async throws {
    let radio = LocationRadio(latitude: 10, longitude: -20, gps: "1")
    await radio.failNextGPSVerification()
    let (service, session) = try await makeLocationService(radio: radio)
    defer { Task { await session.stop() } }
    await radio.resetTrace()

    await #expect(throws: SettingsServiceError.self) {
      try await service.setLatitudeVerified(11)
    }
    #expect(await radio.coordinateWrites.isEmpty)
    #expect(await radio.gpsValue == "1")
    await expectInOrder(radio.trace, ["getCustomVars", "setCustomVar gps:0", "getCustomVars"])
  }

  @Test
  @MainActor
  func `a rejected coordinate write leaves GPS off and the next axis edit still runs`() async throws {
    let radio = LocationRadio(latitude: 10, longitude: -20, gps: "1")
    await radio.failNextCoordinateVerification()
    let (service, session) = try await makeLocationService(radio: radio)
    defer { Task { await session.stop() } }
    await radio.resetTrace()

    await #expect(throws: SettingsServiceError.self) {
      try await service.setLongitudeVerified(-21)
    }
    #expect(await radio.gpsValue == "0")
    #expect(await radio.customVarWrites == ["gps:0"])
    #expect(await radio.latitudeValue == 10)
    #expect(await radio.longitudeValue == -20)

    _ = try await service.setLatitudeVerified(12)
    #expect(await radio.latitudeValue == 12)
    #expect(await radio.longitudeValue == -20)
    #expect(await radio.gpsValue == "0")
    #expect(await radio.customVarWrites == ["gps:0"])
  }

  private func makeLocationService(
    radio: LocationRadio
  ) async throws -> (SettingsService, MeshCoreSession) {
    let session = MeshCoreSession(transport: radio)
    try await session.start()
    return (SettingsService(session: session), session)
  }

  private func expectInOrder(_ trace: [String], _ expected: [String]) {
    var remainder = trace[...]
    for step in expected {
      guard let found = remainder.firstIndex(of: step) else {
        Issue.record("Missing \(step) after \(Array(trace.prefix(trace.count - remainder.count))) in \(trace)")
        return
      }
      remainder = remainder[remainder.index(after: found)...]
    }
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

private actor LocationRadio: MeshTransport {
  private let inner = MockTransport()
  private var latitude: Double
  private var longitude: Double
  private var gps: String?
  private var latitudeAfterGPSOff: Double?
  private var longitudeAfterGPSOff: Double?
  private var failGPSVerification = false
  private var failCoordinateVerification = false
  private(set) var trace: [String] = []
  private(set) var coordinateWrites: [(latitude: Double, longitude: Double)] = []
  private(set) var customVarWrites: [String] = []

  var gpsValue: String? {
    gps
  }

  var latitudeValue: Double {
    latitude
  }

  var longitudeValue: Double {
    longitude
  }

  init(latitude: Double, longitude: Double, gps: String?) {
    self.latitude = latitude
    self.longitude = longitude
    self.gps = gps
  }

  func setCoordinatesAfterGPSOff(latitude: Double? = nil, longitude: Double? = nil) {
    if let latitude { latitudeAfterGPSOff = latitude }
    if let longitude { longitudeAfterGPSOff = longitude }
  }

  func setGPS(_ value: String?) {
    gps = value
  }

  func failNextGPSVerification() {
    failGPSVerification = true
  }

  func failNextCoordinateVerification() {
    failCoordinateVerification = true
  }

  func resetTrace() {
    trace.removeAll()
    coordinateWrites.removeAll()
    customVarWrites.removeAll()
  }

  func connect() async throws {
    try await inner.connect()
  }

  func disconnect() async {
    await inner.disconnect()
  }

  var isConnected: Bool {
    get async { await inner.isConnected }
  }

  var receivedData: AsyncStream<Data> {
    get async { await inner.receivedData }
  }

  func send(_ data: Data) async throws {
    try await inner.send(data)
    switch data.first.flatMap(CommandCode.init(rawValue:)) {
    case .appStart:
      trace.append("appStart")
      await inner.simulateReceive(selfInfoPacket())
    case .getCustomVars:
      trace.append("getCustomVars")
      var packet = Data([ResponseCode.customVars.rawValue])
      if let gps {
        packet.append(contentsOf: "gps:\(gps)".utf8)
      }
      await inner.simulateReceive(packet)
    case .setCustomVar:
      let body = String(data: data.dropFirst(), encoding: .utf8) ?? ""
      trace.append("setCustomVar \(body)")
      customVarWrites.append(body)
      if body == "gps:0", !failGPSVerification {
        gps = "0"
        if let latitudeAfterGPSOff { latitude = latitudeAfterGPSOff }
        if let longitudeAfterGPSOff { longitude = longitudeAfterGPSOff }
      }
      failGPSVerification = false
      await inner.simulateOK()
    case .setCoordinates:
      let requested = (latitude: coordinate(data, at: 1), longitude: coordinate(data, at: 5))
      trace.append("setCoordinates")
      coordinateWrites.append(requested)
      if !failCoordinateVerification {
        latitude = requested.latitude
        longitude = requested.longitude
      }
      failCoordinateVerification = false
      await inner.simulateOK()
    default:
      await inner.simulateError(code: ErrorCode.unsupportedCommand.rawValue)
    }
  }

  private func coordinate(_ data: Data, at offset: Int) -> Double {
    let bits = UInt32(data[offset])
      | UInt32(data[offset + 1]) << 8
      | UInt32(data[offset + 2]) << 16
      | UInt32(data[offset + 3]) << 24
    return Double(Int32(bitPattern: bits)) / 1_000_000
  }

  private func selfInfoPacket() -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(22)
    payload.append(22)
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(contentsOf: int32Bytes(Int32((latitude * 1_000_000).rounded())))
    payload.append(contentsOf: int32Bytes(Int32((longitude * 1_000_000).rounded())))
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(contentsOf: uint32Bytes(915_000))
    payload.append(contentsOf: uint32Bytes(125_000))
    payload.append(7)
    payload.append(5)
    payload.append(contentsOf: "Test".utf8)
    return Data([ResponseCode.selfInfo.rawValue]) + payload
  }
}

private func int32Bytes(_ value: Int32) -> [UInt8] {
  uint32Bytes(UInt32(bitPattern: value))
}

private func uint32Bytes(_ value: UInt32) -> [UInt8] {
  [
    UInt8(value & 0xFF),
    UInt8((value >> 8) & 0xFF),
    UInt8((value >> 16) & 0xFF),
    UInt8((value >> 24) & 0xFF),
  ]
}
