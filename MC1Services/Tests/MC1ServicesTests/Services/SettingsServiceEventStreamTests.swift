import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("SettingsService event stream")
struct SettingsServiceEventStreamTests {
  private actor EventCollector {
    private(set) var events: [SettingsEvent] = []
    func record(_ event: SettingsEvent) {
      events.append(event)
    }

    var count: Int {
      events.count
    }

    var snapshot: [SettingsEvent] {
      events
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

    try await waitUntil("the replacement subscriber must receive the settings event") {
      await collector.count > 0
    }
    let events = await collector.snapshot
    collectTask.cancel()

    guard case let .deviceObserved(info) = events.first else {
      Issue.record("Expected a device observation, got \(String(describing: events.first))")
      return
    }
    #expect(events.count == 1)
    #expect(info.name == "Test")
    #expect(info.publicKey == Data(repeating: 0x01, count: 32))
    #expect(info.latitude == 0)
    #expect(info.longitude == 0)
    #expect(info.txPower == 22)
    let radio = NodeConfigService.buildRadioSettings(from: info)
    #expect(radio.frequency == 915_000)
    #expect(radio.bandwidth == 125_000)
    #expect(radio.spreadingFactor == 7)
    #expect(radio.codingRate == 5)
  }

  @Test
  @MainActor
  func `a failed refresh emits no observation`() async throws {
    let radio = ScriptedRadio(state: .defaults)
    let session = MeshCoreSession(transport: radio)
    try await session.start()
    defer { Task { await session.stop() } }
    let service = SettingsService(session: session)
    let collector = EventCollector()
    let collectTask = Task {
      for await event in await service.events() {
        await collector.record(event)
      }
    }
    defer { collectTask.cancel() }

    await radio.armFailNextAppStart()
    await #expect(throws: (any Error).self) {
      try await service.refreshDeviceInfo()
    }
    #expect(await collector.count == 0)
  }

  @Test
  @MainActor
  func `a refresh queued behind a held preset observes the completed radio`() async throws {
    let usa = try #require(RadioPresets.all.first { $0.id == "us-ca" })
    let radio = ScriptedRadio(state: .defaults)
    let session = MeshCoreSession(transport: radio)
    try await session.start()
    defer {
      Task {
        await radio.releaseSetRadio()
        await session.stop()
      }
    }
    let service = SettingsService(session: session)
    let collector = EventCollector()
    let collectTask = Task {
      for await event in await service.events() {
        await collector.record(event)
      }
    }
    defer { collectTask.cancel() }

    await radio.armSetRadioHold()
    let presetTask = Task { try await service.applyRadioPresetVerified(usa) }
    try await waitUntil("the preset should hold its radio write") {
      await radio.setRadioIsHeld
    }
    let refreshTask = Task { try await service.refreshDeviceInfo() }
    try await waitUntil("the refresh should wait on the RF owner") {
      await service.radioSerializer.waiterCountForTest == 1
    }

    await radio.releaseSetRadio()
    _ = try await presetTask.value
    try await refreshTask.value
    try await waitUntil("the refresh should publish the completed radio") {
      await collector.snapshot.contains { event in
        if case .deviceObserved = event { return true }
        return false
      }
    }

    let events = await collector.snapshot
    let presetIndex = try #require(events.firstIndex { event in
      guard case let .radioUpdated(change) = event else { return false }
      return change.catalogStamp == .set(usa.id)
        && change.frequencyKHz == usa.frequencyKHz
        && change.bandwidthHz == usa.bandwidthHz
        && change.spreadingFactor == usa.spreadingFactor
        && change.codingRate == usa.codingRate
    })
    let observedIndex = try #require(events.firstIndex { event in
      if case .deviceObserved = event { return true }
      return false
    })
    #expect(presetIndex < observedIndex)
    guard case let .deviceObserved(info) = events[observedIndex] else {
      Issue.record("Expected the queued refresh to observe the preset radio")
      return
    }
    let observed = NodeConfigService.buildRadioSettings(from: info)
    #expect(observed.frequency == usa.frequencyKHz)
    #expect(observed.bandwidth == usa.bandwidthHz)
    #expect(observed.spreadingFactor == usa.spreadingFactor)
    #expect(observed.codingRate == usa.codingRate)
  }

  @Test
  @MainActor
  func `a preset capability timeout publishes verified RF and releases ownership`() async throws {
    let hungary = try #require(RadioPresets.all.first { $0.id == "hu" })
    let usa = try #require(RadioPresets.all.first { $0.id == "us-ca" })
    let radio = ScriptedRadio(state: .defaults)
    let clock = TestClock()
    let session = MeshCoreSession(transport: radio, clock: clock)
    try await session.start()
    defer { Task { await session.stop() } }
    let service = SettingsService(session: session)
    let stream = await service.events()
    let collector = EventCollector()
    let collectTask = Task {
      for await event in stream {
        await collector.record(event)
      }
    }
    defer { collectTask.cancel() }

    await radio.armDropNextDeviceInfo()
    let applying = Task { try await service.applyRadioPresetVerified(hungary) }
    try await waitUntil("the capability query should be waiting for its dropped reply") {
      await radio.deviceQueryCount == 1 && clock.sleeperCount == 1
    }
    clock.advance(by: .seconds(SessionConfiguration.default.defaultTimeout))
    do {
      _ = try await applying.value
      Issue.record("Expected the capability query to time out")
    } catch SettingsServiceError.sessionError(.timeout) {}

    #expect(await radio.setRadioCount == 1)
    #expect(await radio.frequencyKHz == hungary.frequencyKHz)
    #expect(await radio.bandwidthHz == hungary.bandwidthHz)
    #expect(await radio.spreadingFactor == hungary.spreadingFactor)
    #expect(await radio.codingRate == hungary.codingRate)

    _ = try await service.applyRadioPresetVerified(usa)
    try await waitUntil("the later preset should publish after the capability timeout") {
      await collector.snapshot.contains { event in
        guard case let .radioUpdated(change) = event else { return false }
        return change.catalogStamp == .set(usa.id)
      }
    }
    let events = await collector.snapshot
    #expect(events.count == 2)
    let changes = events.compactMap { event -> SettingsEvent.RadioChange? in
      guard case let .radioUpdated(change) = event else { return nil }
      return change
    }
    #expect(changes.count == 2)
    let first = try #require(changes.first)
    #expect(first.frequencyKHz == hungary.frequencyKHz)
    #expect(first.bandwidthHz == hungary.bandwidthHz)
    #expect(first.spreadingFactor == hungary.spreadingFactor)
    #expect(first.codingRate == hungary.codingRate)
    #expect(first.catalogStamp == .reconcile)
    let last = try #require(changes.last)
    #expect(last.frequencyKHz == usa.frequencyKHz)
    #expect(last.bandwidthHz == usa.bandwidthHz)
    #expect(last.spreadingFactor == usa.spreadingFactor)
    #expect(last.codingRate == usa.codingRate)
    #expect(last.catalogStamp == .set(usa.id))
    #expect(!changes.contains { $0.catalogStamp == .set(hungary.id) })
    #expect(await radio.setRadioCount == 2)
    #expect(await radio.frequencyKHz == usa.frequencyKHz)
    #expect(await radio.bandwidthHz == usa.bandwidthHz)
    #expect(await radio.spreadingFactor == usa.spreadingFactor)
    #expect(await radio.codingRate == usa.codingRate)
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
