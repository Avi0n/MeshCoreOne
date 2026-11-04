import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Radio WiFi shared observation contract")
@MainActor
struct RadioWiFiContractObservationTests {
  @Test(arguments: [
    (false, "get wifi.ssid", "initial", L10n.Settings.DeviceInfo.unknown),
    (false, "get wifi.status", "confirmed", L10n.Settings.DeviceInfo.unknown),
    (false, "get wifi.ip", "confirmed", L10n.Settings.RadioWiFi.Status.notConnected),
    (true, "get wifi.ssid", "initial", L10n.Settings.DeviceInfo.unknown),
    (true, "get wifi.status", "confirmed", L10n.Settings.DeviceInfo.unknown),
    (true, "get wifi.ip", "confirmed", L10n.Settings.RadioWiFi.Status.connected)
  ])
  func `partial read keeps latest enabled`(
    _ initialEnabled: Bool, _ failedCommand: String, _ expectedNetwork: String, _ expectedStatus: String
  ) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      var firmware = RadioWiFiFirmware(defaultSSID: "initial")
      firmware.enabled = initialEnabled
      firmware.boot()
      await harness.transport.useFirmware(firmware)
      await harness.viewModel.noteSessionReadiness()
      let enabledObservationMatchesContract1 = harness.viewModel.wifiEnabled == initialEnabled
      #expect(enabledObservationMatchesContract1)
      let networkObservationMatchesContract1 = harness.viewModel.networkName == "initial"
      #expect(networkObservationMatchesContract1)
      let tools = harness.makeTools()
      tools.executeCommand("set wifi.enabled \(initialEnabled ? "0" : "1")")
      try await waitUntil("tracked Tools mutation settles") { !tools.isWaitingForResponse }
      #expect(harness.settings?.radioWiFiState.rebootPending == true)
      let settings = try #require(harness.settings)
      try await settings.setRadioCLIValue("wifi.ssid", to: "confirmed")
      await harness.transport.rejectNext(failedCommand)

      await harness.viewModel.refresh()

      let enabledObservationMatchesContract2 = harness.viewModel.wifiEnabled == !initialEnabled
      #expect(enabledObservationMatchesContract2)
      let errorPresentationMatchesContract1 = harness.viewModel.errorMessage != nil
      #expect(errorPresentationMatchesContract1)
      #expect(harness.viewModel.networkValue == expectedNetwork)
      #expect(harness.viewModel.statusValue == expectedStatus)
      if failedCommand != "get wifi.ssid" {
        let networkObservationMatchesContract2 = harness.viewModel.networkName == "confirmed"
        #expect(networkObservationMatchesContract2)
      }
      if initialEnabled {
        let rebootCopyMatchesContract1 = harness.viewModel.rebootMessage == L10n.Settings.RadioWiFi.Alert.Reboot.disabled
        #expect(rebootCopyMatchesContract1)
      } else if failedCommand == "get wifi.ssid" {
        let rebootCopyMatchesContract2 = harness.viewModel.rebootMessage == L10n.Settings.RadioWiFi.Alert.Reboot.restart
        #expect(rebootCopyMatchesContract2)
      } else {
        let rebootCopyMatchesContract3 = harness.viewModel.rebootMessage == L10n.Settings.RadioWiFi.Alert.Reboot.join("confirmed")
        #expect(rebootCopyMatchesContract3)
      }

      await harness.viewModel.refresh()
      let errorPresentationMatchesContract2 = harness.viewModel.errorMessage == nil
      #expect(errorPresentationMatchesContract2)
      let enabledObservationMatchesContract3 = harness.viewModel.wifiEnabled == !initialEnabled
      #expect(enabledObservationMatchesContract3)
      let networkObservationMatchesContract3 = harness.viewModel.networkName == "confirmed"
      #expect(networkObservationMatchesContract3)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == (initialEnabled ? "0" : "1"))
    }
  }

  @Test(arguments: [
    ("", L10n.Settings.RadioWiFi.Network.notSet),
    ("(not connected)", "(not connected)")
  ])
  func `partial read preserves known network knowledge`(_ storedSSID: String, _ expectedNetwork: String) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      var firmware = RadioWiFiFirmware()
      firmware.savedSSID = "initial"
      firmware.boot()
      await harness.transport.useFirmware(firmware)
      await harness.viewModel.noteSessionReadiness()
      let settings = try #require(harness.settings)
      try await settings.setRadioCLIValue("wifi.ssid", to: storedSSID)
      await harness.transport.rejectNext("get wifi.status")

      await harness.viewModel.refresh()

      let networkObservationMatchesContract4 = harness.viewModel.networkName == (storedSSID.isEmpty ? nil : storedSSID)
      #expect(networkObservationMatchesContract4)
      #expect(harness.viewModel.networkValue == expectedNetwork)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      let errorPresentationMatchesContract3 = harness.viewModel.errorMessage != nil
      #expect(errorPresentationMatchesContract3)
      await harness.viewModel.refresh()
      let errorPresentationMatchesContract4 = harness.viewModel.errorMessage == nil
      #expect(errorPresentationMatchesContract4)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "1")
    }
  }
}
