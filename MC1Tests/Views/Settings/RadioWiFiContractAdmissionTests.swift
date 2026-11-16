import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Radio WiFi complete admission certificates")
@MainActor
struct RadioWiFiContractAdmissionTests {
  @Test(arguments: ["save", "disable", "clear", "reboot", "raw-read", "raw-setter"])
  func `old context cannot admit on equal revision successor`(_ action: String) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      let settings = try #require(harness.settings)
      let old = settings.radioWiFiBinding
      let oldWire = harness.transport
      let editorID = UUID()
      let baseline = try #require(try await settings.readRadioWiFiCredentials(editorID: editorID))
      let successor = try await harness.replaceSettingsRuntime()
      let viewStateMatchesContract1 = successor.radioWiFiState.revision == baseline.revision
      #expect(viewStateMatchesContract1)
      let before = await harness.transport.sentData.count
      let oldFrames = await oldWire.sentData.count
      for target in [settings, successor] {
        await #expect {
          switch action {
          case "save":
            _ = try await target.saveRadioWiFiCredentials(
              ssid: "stale", password: baseline.password, baseline: baseline, editorID: editorID,
              ownerIsCurrent: { true }
            )
          case "disable":
            _ = try await target.setRadioWiFiEnabled(
              false, expectedContextID: old.contextID, expectedRevision: baseline.revision
            )
          case "clear":
            _ = try await target.clearRadioWiFi(
              expectedContextID: old.contextID, expectedRevision: baseline.revision
            )
          case "reboot":
            try await target.reboot(expectedContextID: old.contextID, expectedRevision: baseline.revision)
          case "raw-read":
            _ = try await target.runCliCommand("get wifi.ssid", expectedContextID: old.contextID)
          default:
            _ = try await target.runCliCommand("set wifi.ssid stale", expectedContextID: old.contextID)
          }
        } throws: { $0 is CancellationError }
      }
      #expect(await oldWire.sentData.count == oldFrames)
      #expect(await harness.transport.sentData.count == before)
      let viewStateMatchesContract2 = successor.radioWiFiState.revision == baseline.revision
      #expect(viewStateMatchesContract2)
      #expect(!settings.radioWiFiState.rebootPending)
      #expect(!successor.radioWiFiState.rebootPending)
      let current = successor.radioWiFiBinding
      let settled = try await successor.setRadioWiFiEnabled(
        false, expectedContextID: current.contextID, expectedRevision: baseline.revision
      )
      #expect(settled == successor.radioWiFiState.stableRevision())
      #expect(await harness.transport.count("set wifi.enabled 0") == 1)
      #expect(try await successor.getRadioCLIValue("wifi.enabled") == "0")
    }
  }

  @Test
  func `exhausted correlation rejects mutation without pending evidence`() async throws {
    let scope = RadioCLICorrelationScope()
    for _ in 0..<RadioCLICorrelationScope.capacity {
      _ = try scope.allocate()
    }
    try await RadioWiFiContractHarness.withHarness(scope: scope) { harness in
      let settings = try #require(harness.settings)
      let binding = settings.radioWiFiBinding
      let before = await harness.transport.sentData.count
      await #expect {
        try await settings.setRadioWiFiEnabled(
          false, expectedContextID: binding.contextID, expectedRevision: 0
        )
      } throws: { error in
        guard let error = error as? SettingsServiceError,
              case .sessionError(.invalidInput) = error else { return false }
        return true
      }
      #expect(await harness.transport.sentData.count == before)
      #expect(!settings.radioWiFiState.rebootPending)
      #expect(settings.radioWiFiState.stableRevision() != nil)
      #expect(try await settings.runCliCommand("get wifi.enabled") == "> 1")
    }
    try await RadioWiFiContractHarness.withHarness { harness in
      let settings = try #require(harness.settings)
      let binding = settings.radioWiFiBinding
      let settled = try await settings.setRadioWiFiEnabled(
        false, expectedContextID: binding.contextID, expectedRevision: 0
      )
      #expect(settled != nil)
      #expect(settings.radioWiFiState.rebootPending)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "0")
    }
  }

  @Test(arguments: [false, true])
  func `rejected local preflight cannot invent pending evidence`(_ reusesPrefix: Bool) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      let settings = try #require(harness.settings)
      if reusesPrefix { _ = try await settings.runCliCommand("z9|get wifi.enabled") }
      let before = await harness.transport.sentData.count
      let command = reusesPrefix ? "z9|set wifi.enabled 0"
        : "set wifi.ssid " + String(repeating: "x", count: PacketBuilder.runCliCommandMaxUTF8Bytes)
      await #expect {
        try await settings.runCliCommand(command)
      } throws: { error in
        guard let error = error as? SettingsServiceError,
              case .sessionError(.invalidInput) = error else { return false }
        return true
      }
      #expect(await harness.transport.sentData.count == before)
      #expect(!settings.radioWiFiState.rebootPending)
      #expect(settings.radioWiFiState.stableRevision() != nil)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "1")
      try await settings.setRadioCLIValue("wifi.ssid", to: "after-rejection")
      #expect(settings.radioWiFiState.rebootPending)
      #expect(try await settings.getRadioCLIValue("wifi.ssid") == "after-rejection")
    }
  }

  @Test
  func `unreflected replies cannot grant support or credentials and fresh reflection recovers`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.transport.setReflectsPrefixes(false)
      let read = harness.start { await harness.viewModel.noteSessionReadiness() }
      try await waitUntil("the unreflected enabled read owns a real timeout") {
        await harness.transport.count("get wifi.enabled") == 1 && harness.clock.sleeperCount > 0
      }
      harness.clock.advance(by: .seconds(31))
      await read.value
      let viewStateMatchesContract3 = !harness.viewModel.showsSection
      #expect(viewStateMatchesContract3)
      #expect(harness.viewModel.networkValue == L10n.Settings.DeviceInfo.unknown)
      #expect(harness.viewModel.statusValue == L10n.Settings.DeviceInfo.unknown)
      let viewStateMatchesContract4 = !harness.viewModel.canEditNetwork
      #expect(viewStateMatchesContract4)
      let saveEligibilityMatchesContract1 = !harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract1)
      #expect(await harness.transport.count("get wifi.ssid") == 0)
      #expect(await harness.transport.count("get wifi.pwd") == 0)
      await harness.transport.setReflectsPrefixes(true)
      await harness.viewModel.refresh()
      let viewStateMatchesContract5 = harness.viewModel.canEditNetwork
      #expect(viewStateMatchesContract5)
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "after-reflection"
      let saveEligibilityMatchesContract2 = harness.viewModel.canSave
      #expect(saveEligibilityMatchesContract2)
      await harness.viewModel.requestSave()
      #expect(try await harness.settings?.getRadioCLIValue("wifi.ssid") == "after-reflection")
    }
  }

  @Test
  func `rejected enabled receipt keeps its actual settlement and throwing wrapper`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      let settings = try #require(harness.settings)
      let binding = settings.radioWiFiBinding
      await harness.transport.rejectNext("set wifi.enabled 0")
      let receipt = try await settings.changeRadioWiFiEnabled(
        false, expectedContextID: binding.contextID, expectedRevision: 0
      )
      let rejected = if case .rejected = receipt.outcome { true } else { false }
      #expect(rejected)
      #expect(receipt.contextID == binding.contextID)
      #expect(receipt.revision == settings.radioWiFiState.stableRevision())
      #expect(receipt.error != nil)
      #expect(!settings.radioWiFiState.rebootPending)
      #expect(await harness.transport.count("set wifi.enabled 0") == 1)
      let revision = try #require(receipt.revision)
      await harness.transport.rejectNext("set wifi.enabled 0")
      await #expect {
        try await settings.setRadioWiFiEnabled(
          false, expectedContextID: binding.contextID, expectedRevision: revision
        )
      } throws: { error in
        guard let error = error as? SettingsServiceError, case .radioCLIRejected = error else { return false }
        return true
      }
      #expect(!settings.radioWiFiState.rebootPending)
      #expect(await harness.transport.count("set wifi.enabled 0") == 2)
      #expect(try await settings.getRadioCLIValue("wifi.enabled") == "1")
    }
  }
}
