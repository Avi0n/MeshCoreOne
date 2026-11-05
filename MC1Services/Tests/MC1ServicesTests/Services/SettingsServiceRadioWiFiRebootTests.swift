import Foundation
@testable import MC1Services
import Testing

@Suite("SettingsService WiFi reboot evidence")
@MainActor
struct SettingsServiceRadioWiFiRebootTests {
  @Test
  func `generic reboot does not require WiFi apply evidence`() async throws {
    let mock = MockConfigurationSession()
    let service = SettingsService(session: mock)
    #expect(!service.radioWiFiState.rebootPending)

    try await service.reboot()

    #expect(await mock.rebootCallCount == 1)
    #expect(await mock.coreStatsReadCount == 0)
    #expect(service.radioWiFiState.expectedRebootID == nil)
    await service.retireRadioWiFiOperations()
    await #expect(throws: CancellationError.self) { try await service.reboot() }
    #expect(await mock.rebootCallCount == 1)
  }

  @Test
  func `a retired reboot cannot replace the current service submitted candidate`() async throws {
    let oldMock = MockConfigurationSession()
    await oldMock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    await oldMock.failReboot(WiFiTransportError.notConnected)
    let state = RadioWiFiState(radioID: UUID())
    let retired = SettingsService(session: oldMock, radioWiFiState: state)
    try await retired.setRadioCLIValue("wifi.enabled", to: "0")
    await retired.retireRadioWiFiOperations()
    let currentMock = MockConfigurationSession()
    let current = SettingsService(session: currentMock, radioWiFiState: state)
    try await current.reboot()
    let submitted = try #require(state.expectedRebootID)

    await #expect(throws: CancellationError.self) { try await retired.reboot() }

    #expect(state.expectedRebootID == submitted)
    #expect(await oldMock.rebootCalled == false)
    #expect(await currentMock.rebootCalled)
    await current.retireRadioWiFiOperations()
  }

  @Test
  func `retirement joins the actual reboot after its caller is cancelled`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let state = RadioWiFiState(radioID: UUID())
    let old = SettingsService(session: mock, radioWiFiState: state)
    try await old.setRadioCLIValue("wifi.enabled", to: "0")
    await mock.holdNextReboot()
    let caller = Task { try await old.reboot() }
    var retirement: Task<Void, Never>?
    var retirementFinished = false
    do {
      try await waitUntil("reboot physical completion should remain held") { await mock.rebootHeld }
      caller.cancel()
      retirement = Task {
        await old.retireRadioWiFiOperations()
        retirementFinished = true
      }
      try await waitUntil("retirement should close admission") { await old.radioWiFiOperationsRetired }
      #expect(await old.radioWiFiWorkers.count == 1)
      #expect(!retirementFinished)
      await mock.releaseReboot()
      try await caller.value
      await retirement?.value
      #expect(retirementFinished)
      state.noteLinkEnded(contextID: old.radioWiFiContextID)
      let currentMock = MockConfigurationSession()
      let current = SettingsService(session: currentMock, radioWiFiState: state)
      try await current.reboot()
      let submitted = try #require(state.expectedRebootID)
      await #expect(throws: CancellationError.self) { try await old.reboot() }
      #expect(state.expectedRebootID == submitted)
      #expect(await currentMock.rebootCalled)
      await current.retireRadioWiFiOperations()
    } catch {
      await mock.releaseReboot()
      _ = await caller.result
      await retirement?.value
      await old.retireRadioWiFiOperations()
      throw error
    }
  }

  @Test
  func `submitted reboot candidate survives an early ended link until supported reset proof`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let state = RadioWiFiState(radioID: UUID())
    let first = SettingsService(session: mock, radioWiFiState: state)
    try await first.setRadioCLIValue("wifi.enabled", to: "0")
    await mock.setCoreStatsUptime(1000)

    await mock.holdNextReboot()
    let reboot = Task { try await first.reboot() }
    do {
      try await waitUntil("reboot write held") { await mock.rebootHeld }
    } catch {
      await mock.releaseReboot()
      _ = try? await reboot.value
      throw error
    }
    guard let candidate = state.expectedRebootID else {
      await mock.releaseReboot()
      _ = try? await reboot.value
      Issue.record("expected an unsubmitted reboot candidate")
      return
    }
    state.noteLinkEnded(contextID: first.radioWiFiContextID)
    #expect(!state.clearExpectedReboot(
      id: candidate, contextID: UUID(), readRevision: state.revision
    ))
    await mock.releaseReboot()
    try await reboot.value
    await mock.setCoreStatsUptime(1)
    let replacement = SettingsService(session: mock, radioWiFiState: state)
    let statsReads = await mock.coreStatsReadCount
    let wrongRadioCleared = await replacement.confirmExpectedRadioWiFiReboot(
      id: candidate, expectedRadioID: UUID(), ownerIsCurrent: { true }
    )
    #expect(!wrongRadioCleared)
    #expect(await mock.coreStatsReadCount == statsReads)
    #expect(state.rebootPending)

    #expect(await replacement.confirmExpectedRadioWiFiReboot(
      id: candidate, expectedRadioID: state.radioID, ownerIsCurrent: { true }
    ))
    #expect(!state.rebootPending)
    await first.retireRadioWiFiOperations()
    await replacement.retireRadioWiFiOperations()
  }

  @Test
  func `failed reboot submission cannot clear pending evidence`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let state = RadioWiFiState(radioID: UUID())
    let service = SettingsService(session: mock, radioWiFiState: state)
    try await service.setRadioCLIValue("wifi.enabled", to: "0")
    await mock.failReboot(WiFiTransportError.sendTimeout)

    await #expect(throws: WiFiTransportError.self) { try await service.reboot() }

    #expect(state.expectedRebootID == nil)
    #expect(state.rebootPending)
    await service.retireRadioWiFiOperations()
  }

  @Test
  func `ordinary same-radio replacement retains apply evidence`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let state = RadioWiFiState(radioID: UUID())
    let first = SettingsService(session: mock, radioWiFiState: state)
    try await first.setRadioCLIValue("wifi.enabled", to: "0")
    state.noteLinkEnded(contextID: first.radioWiFiContextID)
    await first.retireRadioWiFiOperations()
    let replacement = SettingsService(session: mock, radioWiFiState: state)

    guard case let .supported(probe) = try await replacement.probeRadioWiFiSettings() else {
      throw SettingsServiceError.invalidResponse
    }
    let observation = try #require(await replacement.readRadioWiFiObservation(probe: probe))
    #expect(observation.error == nil)
    #expect(observation.networkNameWasRead)
    #expect(observation.associated != nil)
    #expect(observation.ipAddressWasRead)
    #expect(state.expectedRebootID == nil)
    #expect(state.rebootPending)
    await replacement.retireRadioWiFiOperations()
  }
}
