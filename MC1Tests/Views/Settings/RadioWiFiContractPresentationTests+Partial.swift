@testable import MC1
import Testing

extension RadioWiFiContractPresentationTests {
  @Test(arguments: [
    (true, L10n.Settings.RadioWiFi.Status.connected),
    (false, L10n.Settings.RadioWiFi.Status.notConnected)
  ])
  func `confirmed association survives IP timeout`(_ enabled: Bool, _ expectedStatus: String) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      var firmware = RadioWiFiFirmware(defaultSSID: "configured")
      firmware.enabled = enabled
      firmware.boot()
      await harness.transport.useFirmware(firmware)
      await harness.transport.holdNext("get wifi.ip")
      let read = harness.start { await harness.viewModel.noteSessionReadiness() }
      try await waitUntil("the submitted IP read owns a real exchange timeout") {
        await harness.transport.heldCount("get wifi.ip") == 1 && harness.clock.sleeperCount > 0
      }
      harness.clock.advance(by: .seconds(31))
      await read.value

      #expect(harness.viewModel.networkValue == "configured")
      #expect(harness.viewModel.statusValue == expectedStatus)
      #expect(harness.viewModel.wifiEnabled == enabled)
      #expect(harness.viewModel.ipAddress == nil)
      #expect(harness.viewModel.errorMessage != nil)
      #expect(await harness.transport.effectiveCommands() == [
        "get wifi.enabled", "get wifi.ssid", "get wifi.status", "get wifi.ip"
      ])
      harness.viewModel.errorMessage = nil
      #expect(harness.viewModel.networkValue == "configured")
      #expect(harness.viewModel.statusValue == expectedStatus)
      await harness.transport.release("get wifi.ip")
      await harness.viewModel.refresh()
      #expect(harness.viewModel.networkValue == "configured")
      #expect(harness.viewModel.statusValue == expectedStatus)
      #expect(harness.viewModel.errorMessage == nil)
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") != nil)
    }
  }

  @Test(arguments: [
    ("configured", "configured"),
    ("", L10n.Settings.RadioWiFi.Network.notSet)
  ])
  func `failed reload retains previously observed network`(_ ssid: String, _ expectedNetwork: String) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.transport.useFirmware(RadioWiFiFirmware(defaultSSID: ssid))
      await harness.viewModel.noteSessionReadiness()
      #expect(harness.viewModel.networkValue == expectedNetwork)
      await harness.transport.holdNext("get wifi.ssid")
      let read = harness.start { await harness.viewModel.refresh() }
      try await waitUntil("the reload SSID read owns a real exchange timeout") {
        await harness.transport.heldCount("get wifi.ssid") == 1 && harness.clock.sleeperCount > 0
      }

      #expect(harness.viewModel.networkValue == expectedNetwork)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      harness.clock.advance(by: .seconds(31))
      await read.value
      #expect(harness.viewModel.networkValue == expectedNetwork)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.errorMessage != nil)
      #expect(await harness.transport.count("get wifi.status") == 1)
      #expect(await harness.transport.count("get wifi.ip") == 1)
      #expect(await harness.transport.count("get wifi.pwd") == 0)
      harness.viewModel.errorMessage = nil
      #expect(harness.viewModel.networkValue == expectedNetwork)
      await harness.transport.release("get wifi.ssid")
      await harness.viewModel.refresh()
      #expect(harness.viewModel.networkValue == expectedNetwork)
      #expect(harness.viewModel.errorMessage == nil)
      let settings = try #require(harness.settings)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "1")
    }
  }
}
