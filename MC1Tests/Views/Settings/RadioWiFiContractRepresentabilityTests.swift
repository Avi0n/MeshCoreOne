import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Radio WiFi firmware string representability")
@MainActor
struct RadioWiFiContractRepresentabilityTests {
  @Test(arguments: [false, true])
  func `embedded NUL cannot grant draft or save authority`(_ editsPassword: Bool) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      let settings = try #require(harness.settings)
      let capturedBaseline = harness.viewModel.editor?.baseline
      let baseline = try #require(capturedBaseline)
      if editsPassword {
        harness.viewModel.passwordField = "synthetic\0private-value"
      } else {
        harness.viewModel.ssidField = "network\0truncated"
      }
      let invalidDraftWasRejected = !harness.viewModel.canSave
      #expect(invalidDraftWasRejected)
      let representabilityIsExplained = harness.viewModel.editorFooter
        == L10n.Settings.RadioWiFi.Editor.unsupportedCharacter
      #expect(representabilityIsExplained)
      let before = await harness.transport.commands.count
      var rejected = false
      do {
        _ = try await settings.saveRadioWiFiCredentials(
          ssid: harness.viewModel.ssidField, password: harness.viewModel.passwordField,
          baseline: baseline, editorID: baseline.editorID, ownerIsCurrent: { true }
        )
      } catch SettingsServiceError.invalidResponse {
        rejected = true
      }
      #expect(rejected)
      #expect(await harness.transport.commands.count == before)
      #expect(!settings.radioWiFiState.rebootPending)
      let revisionDidNotChange = settings.radioWiFiState.stableRevision() == baseline.revision
      #expect(revisionDidNotChange)
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "after-representability"
      harness.viewModel.passwordField = "synthetic-valid-value"
      let validDraftCanSave = harness.viewModel.canSave
      #expect(validDraftCanSave)
      await harness.viewModel.requestSave()
      #expect(try await settings.getRadioCLIValue("wifi.ssid") == "after-representability")
      let correctPassword = try await settings.getRadioCLIValue("wifi.pwd") == "synthetic-valid-value"
      #expect(correctPassword)
    }
  }

  @Test(arguments: ["wifi.ssid", "wifi.pwd"])
  func `typed setter cannot certify A value after firmware NUL truncation`(_ key: String) async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      let settings = try #require(harness.settings)
      let before = await harness.transport.commands.count
      var rejected = false
      do {
        try await settings.setRadioCLIValue(key, to: "synthetic\0truncated")
      } catch let error as SettingsServiceError {
        switch error {
        case .invalidResponse, .sessionError(.invalidInput): rejected = true
        default: throw error
        }
      }
      #expect(rejected)
      #expect(await harness.transport.commands.count == before)
      #expect(!settings.radioWiFiState.rebootPending)
      #expect(settings.radioWiFiState.stableRevision() != nil)
      try await settings.setRadioCLIValue(key, to: "synthetic-valid-value")
      let correctValue = try await settings.getRadioCLIValue(key) == "synthetic-valid-value"
      #expect(correctValue)
    }
  }

  @Test
  func `raw tools retains submitted NUL bytes and reports actual firmware effect`() async throws {
    try await RadioWiFiContractHarness.withHarness { harness in
      let tools = harness.makeTools()
      let raw = "  n0|set wifi.ssid raw-network\0ignored"
      tools.executeCommand(raw)
      try await waitUntil("the actual raw Tools command settles") { !tools.isWaitingForResponse }
      let preservedBytes = await harness.transport.commands.contains(raw)
      #expect(preservedBytes)
      #expect(tools.outputLines.contains { $0.type == .response && $0.text.hasPrefix("n0|") })
      #expect(try await harness.settings?.getRadioCLIValue("wifi.ssid") == "raw-network")
      await harness.viewModel.noteSessionReadiness()
      harness.viewModel.openEditor()
      await harness.viewModel.loadEditor()
      harness.viewModel.ssidField = "after-raw-bytes"
      let validDraftCanSave = harness.viewModel.canSave
      #expect(validDraftCanSave)
      await harness.viewModel.requestSave()
      #expect(try await harness.settings?.getRadioCLIValue("wifi.ssid") == "after-raw-bytes")
    }
  }
}
