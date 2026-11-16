@testable import MC1
import Testing

extension RadioWiFiContractPresentationTests {
  @Test(arguments: [false, true])
  func `unavailable live context retains only saved observation`(_ missingService: Bool) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      let settings = try #require(harness.settings)
      let before = await harness.transport.commands.count
      if missingService {
        harness.settings = nil
      } else {
        harness.connectionState = .syncing
      }

      await harness.viewModel.noteSessionReadiness()

      #expect(harness.viewModel.showsSection)
      #expect(harness.viewModel.networkValue == "initial")
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      #expect(!harness.viewModel.statusConnected)
      #expect(harness.viewModel.ipAddress == nil)
      #expect(!harness.viewModel.canRunCommands)
      #expect(harness.viewModel.errorMessage == nil)
      #expect(await harness.transport.commands.count == before)
      harness.settings = settings
      harness.connectionState = .ready
      await harness.viewModel.refresh()
      #expect(harness.viewModel.networkValue == "initial")
      #expect(harness.viewModel.statusValue == L10n.Settings.RadioWiFi.Status.connected)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "1")
    }
  }

  @Test
  func `contended accepted network remains visible without current revision`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "accepted-network"
      harness.viewModel.passwordField = "synthetic-contended-secret"
      let command = "set wifi.ssid accepted-network"
      await harness.transport.holdNext(command)
      let save = harness.start { await harness.viewModel.requestSave() }
      try await waitUntil("the real SSID setter is held before its acknowledgement") {
        await harness.transport.heldCount(command) == 1
      }
      let settings = try #require(harness.settings)
      let saveRevision = settings.radioWiFiState.revision
      let tools = harness.makeTools()
      tools.executeCommand("set wifi.enabled 0")
      try await waitUntil("tracked Tools work contends with the accepted SSID setter") {
        settings.radioWiFiState.revision > saveRevision
      }
      await harness.transport.release(command)
      await save.value
      try await waitUntil("the contending Tools command settles") { !tools.isWaitingForResponse }

      #expect(harness.viewModel.editorOpen)
      #expect(harness.viewModel.observation.networkRevision == nil)
      #expect(harness.viewModel.networkValue == "accepted-network")
      #expect(!harness.viewModel.canClear)
      #expect(!harness.viewModel.canSave)
      #expect(harness.viewModel.needsEditorRead)
      #expect(harness.viewModel.rebootMessage == L10n.Settings.RadioWiFi.Alert.Reboot.restart)
      #expect(await harness.transport.count("set wifi.pwd synthetic-contended-secret") == 0)
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "after-contention"
      #expect(harness.viewModel.canSave)
      await harness.viewModel.requestSave()
      #expect(harness.viewModel.networkValue == "after-contention")
      #expect(try await settings.getRadioCLIValue("wifi.ssid") == "after-contention")
    }
  }
}
