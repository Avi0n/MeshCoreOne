@testable import MC1Services
import Testing

@Suite("SettingsService applyRadioPresetVerified path hash")
struct SettingsServiceApplyPresetTests {
  @Test
  @MainActor
  func `Hungary on v10 writes RF and hash then stamps catalog id`() async throws {
    let mock = MockConfigurationSession(firmwareVersion: 10)
    let service = SettingsService(session: mock)
    let preset = try #require(RadioPresets.all.first(where: { $0.id == "hu" }))
    let (collector, collectTask) = await collectSettingsEvents(from: service)
    defer { collectTask.cancel() }

    _ = try await service.applyRadioPresetVerified(preset)

    let radios = await mock.setRadioCalls
    #expect(radios.count == 1)
    #expect(radios[0].frequency == 869.618)
    #expect(await mock.setPathHashModeCalls == [1])
    try await waitUntil("apply should publish path hash then catalog id") {
      await collector.catalogIDs == ["hu"]
    }
    let events = await collector.events
    let pathHash = try #require(events.firstIndex {
      if case .pathHashModeUpdated(1) = $0 { return true }
      return false
    })
    let catalog = try #require(events.firstIndex {
      if case let .deviceUpdated(_, id) = $0 { return id == "hu" }
      return false
    })
    #expect(pathHash < catalog)
    #expect(await collector.deviceUpdatedCount == 1)
  }

  @Test
  @MainActor
  func `Hungary path-hash failure does not stamp catalog id`() async throws {
    let mock = MockConfigurationSession(firmwareVersion: 10)
    let service = SettingsService(session: mock)
    let preset = try #require(RadioPresets.all.first(where: { $0.id == "hu" }))
    let (collector, collectTask) = await collectSettingsEvents(from: service)
    defer { collectTask.cancel() }

    await mock.failNextSetPathHashMode(code: ErrorCode.illegalArgument.rawValue)
    await #expect(throws: SettingsServiceError.self) {
      try await service.applyRadioPresetVerified(preset)
    }
    #expect(await mock.setRadioCalls.count == 1)
    #expect(await mock.setPathHashModeCalls == [1])

    try await service.refreshDeviceInfo()
    try await waitUntil("refreshDeviceInfo flushes the event stream") {
      await collector.deviceUpdatedCount >= 1
    }
    #expect(await collector.catalogIDs.isEmpty)
  }

  @Test
  @MainActor
  func `USA/Canada writes RF, skips hash, and stamps catalog id`() async throws {
    let mock = MockConfigurationSession(firmwareVersion: 10)
    let service = SettingsService(session: mock)
    let preset = try #require(RadioPresets.all.first(where: { $0.id == "us-ca" }))
    let (collector, collectTask) = await collectSettingsEvents(from: service)
    defer { collectTask.cancel() }

    _ = try await service.applyRadioPresetVerified(preset)

    #expect(await mock.setRadioCalls.count == 1)
    #expect(await mock.setPathHashModeCalls.isEmpty)
    try await waitUntil("apply should publish the catalog id") {
      await collector.catalogIDs == ["us-ca"]
    }
    #expect(await collector.deviceUpdatedCount == 1)
  }

  @Test
  @MainActor
  func `Hungary on v9 writes RF and skips hash`() async throws {
    let mock = MockConfigurationSession(firmwareVersion: 9)
    let service = SettingsService(session: mock)
    let preset = try #require(RadioPresets.all.first(where: { $0.id == "hu" }))

    _ = try await service.applyRadioPresetVerified(preset)

    #expect(await mock.setRadioCalls.count == 1)
    #expect(await mock.setPathHashModeCalls.isEmpty)
  }
}

private actor SettingsEventCollector {
  private(set) var events: [SettingsEvent] = []

  func record(_ event: SettingsEvent) {
    events.append(event)
  }

  var catalogIDs: [String] {
    events.compactMap { event in
      guard case let .deviceUpdated(_, id) = event else { return nil }
      return id
    }
  }

  var deviceUpdatedCount: Int {
    events.reduce(into: 0) { count, event in
      if case .deviceUpdated = event { count += 1 }
    }
  }
}

@MainActor
private func collectSettingsEvents(
  from service: SettingsService
) async -> (SettingsEventCollector, Task<Void, Never>) {
  let stream = await service.events()
  let collector = SettingsEventCollector()
  let task = Task {
    for await event in stream {
      await collector.record(event)
    }
  }
  return (collector, task)
}
