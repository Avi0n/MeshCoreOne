import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension RadioWiFiContractPresentationTests {
  @Test(arguments: [false, true])
  func `replacement rejects late observation at equal revision`(_ foreignRadio: Bool) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      let previous = try #require(harness.settings)
      let previousBinding = previous.radioWiFiBinding
      await harness.transport.useFirmware(RadioWiFiFirmware(defaultSSID: "obsolete"))
      await harness.transport.holdNext("get wifi.ssid")
      let read = harness.start { await harness.viewModel.refresh() }
      try await waitUntil("the old observation SSID reply is held") {
        await harness.transport.heldCount("get wifi.ssid") == 1
      }
      await previous.retireRadioWiFiOperations()
      if foreignRadio { harness.device.radioID = UUID() }
      let successor = SettingsService(
        session: harness.session, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID)
      )
      harness.settings = successor
      #expect(successor.radioWiFiState.revision == previousBinding.state.revision)
      #expect(successor.radioWiFiBinding.contextID != previousBinding.contextID)
      await harness.transport.useFirmware(RadioWiFiFirmware(defaultSSID: "successor"))
      await harness.viewModel.noteSessionReadiness()

      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(!harness.viewModel.statusConnected)
      #expect(harness.viewModel.ipAddress == nil)
      await harness.transport.holdNext("get wifi.ssid")
      await harness.transport.release("get wifi.ssid")
      try await waitUntil("the successor SSID read owns a real timeout after the old result") {
        let held = await harness.transport.heldCount("get wifi.ssid")
        let submitted = await harness.transport.count("get wifi.ssid")
        return held == 1 && submitted == 3 && harness.clock.sleeperCount > 0
      }
      #expect(harness.viewModel.showsSection)
      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(await harness.transport.count("get wifi.status") == 1)
      #expect(await harness.transport.count("get wifi.pwd") == 0)
      harness.clock.advance(by: .seconds(31))
      await read.value
      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.errorMessage != nil)
      await harness.transport.release("get wifi.ssid")
      await harness.viewModel.refresh()
      #expect(harness.viewModel.networkValue == "successor")
      #expect(harness.viewModel.statusValue == L10n.Settings.RadioWiFi.Status.connected)
      #expect(harness.viewModel.errorMessage == nil)
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "after-context"
      #expect(harness.viewModel.canSave)
      await harness.viewModel.requestSave()
      #expect(harness.viewModel.networkValue == "after-context")
      #expect(try await successor.getRadioCLIValue("wifi.ssid") == "after-context")
    }
  }

  @Test
  func `fresh session at equal revision starts with unknown observations`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      let previous = try #require(harness.settings)
      let previousBinding = previous.radioWiFiBinding
      let successor = try await harness.replaceSettingsRuntime()
      #expect(successor !== previous)
      #expect(successor.radioWiFiState.revision == previousBinding.state.revision)
      #expect(successor.radioWiFiBinding.contextID != previousBinding.contextID)
      await harness.transport.holdNext("get wifi.ssid")
      let read = harness.start { await harness.viewModel.noteSessionReadiness() }
      try await waitUntil("the fresh runtime SSID read owns a real timeout") {
        await harness.transport.heldCount("get wifi.ssid") == 1 && harness.clock.sleeperCount > 0
      }
      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      harness.clock.advance(by: .seconds(31))
      await read.value
      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      await harness.transport.release("get wifi.ssid")
      await harness.viewModel.refresh()
      #expect(harness.viewModel.networkValue == "initial")
      #expect(harness.viewModel.statusValue == L10n.Settings.RadioWiFi.Status.connected)
      #expect(try await successor.getRadioCLIValue("wifi.enabled") == "1")
    }
  }
}
