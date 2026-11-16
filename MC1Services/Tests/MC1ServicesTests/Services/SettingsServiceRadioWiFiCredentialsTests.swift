import Foundation
@testable import MC1Services
import Testing

@Suite("SettingsService WiFi credentials")
@MainActor
struct SettingsServiceRadioWiFiCredentialsTests {
  @Test
  func `password-only Save materializes the firmware default SSID`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in", defaultPassword: "build-key"))
    let service = SettingsService(session: mock)
    let editorID = UUID()
    let baseline = try #require(try await service.readRadioWiFiCredentials(editorID: editorID))

    let receipt = try await service.saveRadioWiFiCredentials(
      ssid: baseline.ssid, password: "new-key", baseline: baseline, editorID: editorID, ownerIsCurrent: { true }
    )

    #expect(receipt.ssidAccepted)
    #expect(receipt.passwordAccepted)
    #expect(receipt.error == nil)
    let calls = await mock.runCliCommandCalls
    #expect(calls.dropFirst(2).first == "set wifi.ssid built-in")
    #expect(calls.count == 4)
    await mock.bootWiFiFirmware()
    #expect(await mock.wiFiBootUses(ssid: "built-in", password: "new-key"))
    await service.retireRadioWiFiOperations()
  }

  @Test
  func `partial Save keeps the accepted SSID and permits an owned retry`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in", defaultPassword: "build-key"))
    let service = SettingsService(session: mock)
    let editorID = UUID()
    let baseline = try #require(try await service.readRadioWiFiCredentials(editorID: editorID))
    await mock.failWiFiCommand("set wifi.pwd new-key", with: .rejected)

    let partial = try await service.saveRadioWiFiCredentials(
      ssid: "saved", password: "new-key", baseline: baseline, editorID: editorID, ownerIsCurrent: { true }
    )

    #expect(partial.ssidAccepted)
    #expect(!partial.passwordAccepted)
    #expect(!partial.uncertain)
    #expect(partial.error != nil)
    let updated = try #require(partial.updatedBaseline)
    let retried = try await service.saveRadioWiFiCredentials(
      ssid: "saved", password: "new-key", baseline: updated, editorID: editorID, ownerIsCurrent: { true }
    )

    #expect(retried.error == nil)
    #expect(retried.passwordAccepted)
    #expect(await mock.runCliCommandCalls.filter { $0 == "set wifi.ssid saved" }.count == 2)
    await mock.bootWiFiFirmware()
    #expect(await mock.wiFiBootUses(ssid: "saved", password: "new-key"))
    await service.retireRadioWiFiOperations()
  }

  @Test(arguments: [false, true])
  func `a lost setter reply is uncertain and fresh reads determine the stored value`(_ applied: Bool) async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in", defaultPassword: "build-key"))
    let service = SettingsService(session: mock)
    let editorID = UUID()
    let baseline = try #require(try await service.readRadioWiFiCredentials(editorID: editorID))
    await mock.failWiFiCommand("set wifi.ssid submitted", with: applied ? .timeoutAfterApply : .timeoutBeforeApply)

    let receipt = try await service.saveRadioWiFiCredentials(
      ssid: "submitted", password: baseline.password, baseline: baseline, editorID: editorID, ownerIsCurrent: { true }
    )

    #expect(receipt.uncertain)
    #expect(!receipt.ssidAccepted)
    #expect(await service.radioWiFiRebootPending)
    let refreshed = try #require(try await service.readRadioWiFiCredentials(editorID: editorID))
    #expect(refreshed.ssid == (applied ? "submitted" : "built-in"))
    await service.retireRadioWiFiOperations()
  }

  @Test
  func `an explicit empty password replaces a build password after SSID materialization`() async throws {
    let mock = MockConfigurationSession()
    var firmware = RadioWiFiFirmware(defaultSSID: "built-in", defaultPassword: "build-key")
    firmware.savedSSID = "saved"
    firmware.savedPassword = "saved-key"
    firmware.boot()
    await mock.useWiFiFirmware(firmware)
    let service = SettingsService(session: mock)
    let editorID = UUID()
    let baseline = try #require(try await service.readRadioWiFiCredentials(editorID: editorID))

    let receipt = try await service.saveRadioWiFiCredentials(
      ssid: baseline.ssid, password: "", baseline: baseline, editorID: editorID, ownerIsCurrent: { true }
    )

    #expect(receipt.passwordAccepted)
    await mock.bootWiFiFirmware()
    #expect(await mock.wiFiBootUses(ssid: "saved", password: ""))
    await service.retireRadioWiFiOperations()
  }

  @Test
  func `clear reads back the build default without disabling or changing association`() async throws {
    let mock = MockConfigurationSession()
    var firmware = RadioWiFiFirmware(defaultSSID: "built-in", defaultPassword: "build-key")
    firmware.savedSSID = "saved"
    firmware.boot()
    await mock.useWiFiFirmware(firmware)
    let service = SettingsService(session: mock)
    let revision = try #require(service.radioWiFiState.stableRevision())

    let snapshot = try #require(try await service.clearRadioWiFi(expectedContextID: service.radioWiFiContextID, expectedRevision: revision))

    #expect(snapshot.networkName == "built-in")
    #expect(snapshot.enabled)
    #expect(snapshot.associated == true)
    #expect(await mock.wiFiBootUses(ssid: "saved", password: ""))
    await service.retireRadioWiFiOperations()
  }

  @Test
  func `configured disable retains association until boot`() async throws {
    let mock = MockConfigurationSession()
    await mock.useWiFiFirmware(RadioWiFiFirmware(defaultSSID: "built-in"))
    let service = SettingsService(session: mock)
    let revision = try #require(service.radioWiFiState.stableRevision())

    try await service.setRadioWiFiEnabled(false, expectedContextID: service.radioWiFiContextID, expectedRevision: revision)
    guard case let .supported(probe) = try await service.probeRadioWiFiSettings() else {
      throw SettingsServiceError.invalidResponse
    }
    let observation = try #require(await service.readRadioWiFiObservation(probe: probe))

    #expect(observation.error == nil)
    #expect(observation.networkNameWasRead)
    #expect(observation.associated != nil)
    #expect(observation.ipAddressWasRead)
    #expect(!observation.enabled)
    #expect(observation.associated == true)
    await mock.bootWiFiFirmware()
    guard case let .supported(afterBootProbe) = try await service.probeRadioWiFiSettings() else {
      throw SettingsServiceError.invalidResponse
    }
    let afterBoot = try #require(await service.readRadioWiFiObservation(probe: afterBootProbe))
    #expect(afterBoot.error == nil)
    #expect(afterBoot.networkNameWasRead)
    #expect(afterBoot.associated != nil)
    #expect(afterBoot.ipAddressWasRead)
    #expect(afterBoot.associated == false)
    await service.retireRadioWiFiOperations()
  }
}
