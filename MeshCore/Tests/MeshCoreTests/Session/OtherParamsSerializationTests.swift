import Foundation
@testable import MeshCore
import Testing

@Suite("MeshCoreSession other-params serialization")
struct OtherParamsSerializationTests {
  @Test(arguments: [false, true])
  func `full and granular setters preserve acknowledged configuration`(concurrent: Bool) async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    try await startSession(session, transport: transport)
    let device = OtherParamsDevice(transport: transport)
    let responder = Task { await device.run() }
    defer { responder.cancel() }

    let full = Task {
      try await session.setOtherParams(
        manualAddContacts: true, telemetryModeEnvironment: 2,
        telemetryModeLocation: 1, telemetryModeBase: 3,
        advertisementLocationPolicy: 1, multiAcks: 4
      )
    }
    try await waitUntil {
      await transport.sentData.contains { $0.first == CommandCode.setOtherParams.rawValue }
    }
    if !concurrent { try await full.value }
    let granular = Task { try await session.setMultiAcks(7) }
    try await full.value
    try await granular.value

    let final = try #require(await device.lastWrittenConfig)
    #expect(final.manualAddContacts)
    #expect(final.telemetryModeEnvironment == 2)
    #expect(final.telemetryModeLocation == 1)
    #expect(final.telemetryModeBase == 3)
    #expect(final.advertisementLocationPolicy == 1)
    #expect(final.multiAcks == 7)
    await session.stop()
  }

  @Test
  func `omitting multi acks preserves the radio value and refreshes the cache`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    try await startSession(session, transport: transport)
    let device = OtherParamsDevice(transport: transport)
    let responder = Task { await device.run() }
    defer { responder.cancel() }
    try await session.setMultiAcks(7)
    try await session.setOtherParams(
      manualAddContacts: true, telemetryModeEnvironment: 0,
      telemetryModeLocation: 0, telemetryModeBase: 0,
      advertisementLocationPolicy: 0
    )
    #expect(await session.currentSelfInfo?.manualAddContacts == true)
    #expect(await session.currentSelfInfo?.multiAcks == 7)
    #expect(await transport.sentData.last { $0.first == CommandCode.setOtherParams.rawValue }?.count == 4)
    await session.stop()
  }

  /// Two granular setters fired concurrently must both survive. Each setter does a
  /// read-modify-write of the full config across several wire exchanges, so without
  /// serialization the second setter reads the pre-first-write snapshot and reverts
  /// the first setter's change. With serialization the second setter reads the config
  /// the first one already wrote, so the final frame on the wire carries both changes.
  @Test
  func `concurrent granular setters do not revert each other`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )

    try await startSession(session, transport: transport)

    // Emulate a device that persists the full config from each setOtherParams frame
    // and echoes it back in the selfInfo it returns to the post-write appStart.
    let device = OtherParamsDevice(transport: transport)
    let responderTask = Task { await device.run() }
    defer { responderTask.cancel() }

    async let first: Void = session.setManualAddContacts(true)
    async let second: Void = session.setMultiAcks(7)
    try await first
    try await second

    responderTask.cancel()

    let finalConfig = try #require(await device.lastWrittenConfig)
    #expect(finalConfig.manualAddContacts, "manual-add change must survive the concurrent setter")
    #expect(finalConfig.multiAcks == 7, "multi-acks change must survive the concurrent setter")

    await session.stop()
  }

  @Test
  func `partial intent merges inside the owner alongside a granular setter`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)
    let device = OtherParamsDevice(transport: transport)
    let responderTask = Task { await device.run() }
    defer { responderTask.cancel() }

    async let partial: Void = session.applyOtherParams(OtherParamsIntent(multiAcks: 7))
    async let granular: Void = session.setManualAddContacts(true)
    try await partial
    try await granular

    let finalConfig = try #require(await device.lastWrittenConfig)
    #expect(finalConfig.manualAddContacts)
    #expect(finalConfig.multiAcks == 7)
    #expect(finalConfig.advertisementLocationPolicy == 0)
    await session.stop()
  }

  @Test
  func `an empty or unchanged partial intent sends no command`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    try await startSession(session, transport: transport)
    let before = await transport.sentData.count

    try await session.applyOtherParams(OtherParamsIntent())
    try await session.applyOtherParams(OtherParamsIntent(advertisementLocationPolicy: 0, multiAcks: 0))

    #expect(await transport.sentData.count == before)
    await session.stop()
  }
}

/// Background device emulator that tracks running other-params state. Replies OK to each
/// setOtherParams frame after recording it, and replies with a matching selfInfo to the
/// appStart that applyOtherParams issues to refresh the session's cache.
private actor OtherParamsDevice {
  private let transport: MockTransport
  private var processed = 0
  private var current = OtherParamsConfig()
  private(set) var lastWrittenConfig: OtherParamsConfig?

  init(transport: MockTransport) {
    self.transport = transport
  }

  func run() async {
    while !Task.isCancelled {
      let sent = await transport.sentData
      while processed < sent.count {
        let frame = sent[processed]
        processed += 1
        await handle(frame)
      }
      try? await Task.sleep(for: .milliseconds(5))
    }
  }

  private func handle(_ frame: Data) async {
    guard let code = frame.first else { return }
    switch code {
    case CommandCode.setOtherParams.rawValue:
      if let config = Self.decodeSetOtherParams(frame, current: current) {
        current = config
        lastWrittenConfig = config
      }
      await transport.simulateOK()
    case CommandCode.appStart.rawValue:
      await transport.simulateReceive(makeSelfInfoPacket(config: current))
    default:
      break
    }
  }

  /// Decodes a setOtherParams command frame: [cmd][manualAdd][telemetryByte][advLoc][multiAcks].
  private static func decodeSetOtherParams(_ frame: Data, current: OtherParamsConfig) -> OtherParamsConfig? {
    let bytes = Array(frame)
    guard bytes.count >= 4 else { return nil }
    let telemetry = bytes[2]
    return OtherParamsConfig(
      manualAddContacts: bytes[1] != 0,
      telemetryModeBase: telemetry & 0b11,
      telemetryModeLocation: (telemetry >> 2) & 0b11,
      telemetryModeEnvironment: (telemetry >> 4) & 0b11,
      advertisementLocationPolicy: bytes[3],
      multiAcks: bytes.count >= 5 ? bytes[4] : current.multiAcks
    )
  }
}

private func startSession(
  _ session: MeshCoreSession,
  transport: MockTransport
) async throws {
  let startTask = Task { try await session.start() }
  try await waitUntil("transport should send appStart before session starts") {
    await transport.sentData.count == 1
  }
  await transport.simulateReceive(makeSelfInfoPacket(config: OtherParamsConfig()))
  try await startTask.value
}

/// Builds a selfInfo response packet whose other-params bytes reflect `config`.
private func makeSelfInfoPacket(config: OtherParamsConfig) -> Data {
  var payload = Data([ResponseCode.selfInfo.rawValue])
  payload.append(1) // adv type
  payload.append(UInt8(bitPattern: 22)) // tx power
  payload.append(UInt8(bitPattern: 22)) // max tx power
  payload.append(Data(repeating: 0x01, count: 32)) // pubkey
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lat
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lon
  payload.append(config.multiAcks) // multi acks
  payload.append(config.advertisementLocationPolicy) // adv loc policy
  let telemetry = ((config.telemetryModeEnvironment & 0b11) << 4) |
    ((config.telemetryModeLocation & 0b11) << 2) |
    (config.telemetryModeBase & 0b11)
  payload.append(telemetry) // telemetry mode
  payload.append(config.manualAddContacts ? 1 : 0) // manual add
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) }) // freq
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) }) // bw
  payload.append(11) // sf
  payload.append(5) // cr
  return payload
}
