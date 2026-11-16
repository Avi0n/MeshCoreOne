import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Radio WiFi replacement binding contract")
@MainActor
struct RadioWiFiContractBindingTests {
  @Test
  func `old context cannot admit reads on equal revision successor`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      let settings = try #require(harness.settings)
      let previous = settings.radioWiFiBinding
      let oldWire = harness.transport
      let successor = try await harness.replaceSettingsRuntime()
      #expect(successor !== settings)
      #expect(successor.radioWiFiState.revision == previous.state.revision)
      let before = await harness.transport.commands.count

      let oldFrames = await oldWire.sentData.count
      for target in [settings, successor] {
        let capability = try await target.probeRadioWiFiSettings(expectedContextID: previous.contextID)
        let oldProbeWasRejected = if case .busy = capability { true } else { false }
        #expect(oldProbeWasRejected)
        let oldRead = try await target.readRadioWiFiCredentials(
          editorID: UUID(), expectedContextID: previous.contextID
        )
        #expect(oldRead == nil)
      }
      #expect(await oldWire.sentData.count == oldFrames)
      #expect(await harness.transport.commands.count == before)
      let current = successor.radioWiFiBinding
      _ = try await successor.probeRadioWiFiSettings(expectedContextID: current.contextID)
      let fresh = try #require(try await successor.readRadioWiFiCredentials(
        editorID: UUID(), expectedContextID: current.contextID
      ))
      #expect(fresh.contextID == current.contextID)
      #expect(try await successor.getRadioCLIValue("wifi.enabled") == "1")
    }
  }

  @Test
  func `tools intent cannot borrow successor before its task runs`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      let previous = try #require(harness.settings)
      let tools = harness.makeTools()
      let staleCommand = "set wifi.ssid old-intent"

      tools.executeCommand(staleCommand)
      let successor = SettingsService(
        session: harness.session, radioWiFiState: RadioWiFiState(radioID: harness.device.radioID)
      )
      harness.settings = successor
      try await waitUntil("the old UI task completes after service replacement") { !tools.isWaitingForResponse }

      #expect(await harness.transport.count(staleCommand) == 0)
      #expect(!successor.radioWiFiState.rebootPending)
      #expect(!tools.outputLines.contains { $0.type == .response || $0.type == .error })
      tools.executeCommand("set wifi.ssid current-intent")
      try await waitUntil("the fresh Tools intent settles") { !tools.isWaitingForResponse }
      #expect(await harness.transport.count("set wifi.ssid current-intent") == 1)
      #expect(try await successor.getRadioCLIValue("wifi.ssid") == "current-intent")
      await previous.retireRadioWiFiOperations()
    }
  }

  @Test
  func `old read settles before equal revision successor`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()
      await harness.transport.holdNext("get wifi.ssid")
      let read = harness.start { await harness.viewModel.loadEditor() }
      try await waitUntil("old editor SSID frame is held") {
        await harness.transport.heldCount("get wifi.ssid") == 1
      }
      let settings = try #require(harness.settings)
      let previous = settings.radioWiFiBinding
      let oldWire = harness.transport

      await settings.retireRadioWiFiOperations()
      #expect(harness.settings === settings)
      await oldWire.release("get wifi.ssid")
      await read.value
      #expect(await oldWire.count("get wifi.pwd") == 0)
      let successor = try await harness.replaceSettingsRuntime()
      #expect(previous.state.revision == successor.radioWiFiState.revision)
      await harness.viewModel.noteSessionReadiness()

      #expect(await harness.transport.count("get wifi.pwd") == 0)
      let editorPresentationMatchesContract1 = !harness.viewModel.editorOpen
      #expect(editorPresentationMatchesContract1)
      let passwordKnowledgeMatchesContract1 = harness.viewModel.passwordField.isEmpty
      #expect(passwordKnowledgeMatchesContract1)
      let networkKnowledgeMatchesContract1 = harness.viewModel.ssidField.isEmpty
      #expect(networkKnowledgeMatchesContract1)
      let viewStateMatchesContract1 = !harness.viewModel.isBusy
      #expect(viewStateMatchesContract1)
      let networkObservationMatchesContract1 = harness.viewModel.networkName == "initial"
      #expect(networkObservationMatchesContract1)
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "after-binding"
      let saveEligibilityMatchesContract1 = harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract1)
      await harness.viewModel.requestSave()
      #expect(try await successor.getRadioCLIValue("wifi.ssid") == "after-binding")
    }
  }

  @Test(arguments: ["save", "disable", "clear", "reboot"])
  func `binding replacement withdraws all confirmation siblings`(_ action: String) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      harness.transportType = .wifi
      let settings = try #require(harness.settings)
      try await settings.setRadioCLIValue("wifi.ssid", to: "initial")
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "old-draft"
      harness.viewModel.passwordField = "synthetic-old-secret"
      let entry: RadioWiFiViewModel.AlertAction
      switch action {
      case "save":
        await harness.viewModel.requestSave()
        entry = .wifiWarning
      case "disable":
        await harness.viewModel.setWiFiEnabled(false)
        entry = .wifiWarning
      case "clear":
        harness.viewModel.requestClear()
        entry = .clear
      default:
        harness.viewModel.requestReboot()
        entry = .reboot
      }
      let viewStateMatchesContract2 = harness.viewModel.confirmation != nil
      #expect(viewStateMatchesContract2)
      let previous = settings.radioWiFiBinding
      let oldRevision = previous.state.revision

      let successor = try await harness.replaceSettingsRuntime()
      try await successor.setRadioCLIValue("wifi.ssid", to: "successor")
      #expect(successor.radioWiFiBinding.contextID != previous.contextID)
      #expect(successor.radioWiFiState.revision == oldRevision)
      let before = await harness.transport.commands.count
      await harness.viewModel.confirmFromAlert(entry).value

      #expect(await harness.transport.commands.count == before)
      let saveEligibilityMatchesContract2 = !harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract2)
      await harness.viewModel.noteSessionReadiness()
      let editorPresentationMatchesContract2 = !harness.viewModel.editorOpen
      #expect(editorPresentationMatchesContract2)
      let passwordKnowledgeMatchesContract2 = harness.viewModel.passwordField.isEmpty
      #expect(passwordKnowledgeMatchesContract2)
      let networkKnowledgeMatchesContract2 = harness.viewModel.ssidField.isEmpty
      #expect(networkKnowledgeMatchesContract2)
      let viewStateMatchesContract3 = harness.viewModel.confirmation == nil
      #expect(viewStateMatchesContract3)
      let networkObservationMatchesContract2 = harness.viewModel.networkName == "successor"
      #expect(networkObservationMatchesContract2)
      let viewStateMatchesContract4 = harness.viewModel.canRunCommands
      #expect(viewStateMatchesContract4)
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "current-save"
      let saveEligibilityMatchesContract3 = harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract3)
      await harness.viewModel.requestSave()
      await harness.viewModel.confirmFromAlert(.wifiWarning).value
      #expect(try await successor.getRadioCLIValue("wifi.ssid") == "current-save")
    }
  }
}
