import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Radio WiFi observation")
@MainActor
struct RadioWiFiObservationTests {
  @Test
  func `expected reboot readback refreshes the badge without a configuration mutation`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness { harness in
      let appState = harness.appState
      await appState.wireServicesIfConnected()
      let service = try #require(appState.services?.settingsService)
      let viewModel = RadioWiFiViewModel()
      viewModel.configure(
        settingsService: { appState.services?.settingsService },
        connectionState: { appState.connectionState },
        connectedDevice: { appState.connectedDevice },
        currentTransportType: { appState.connectionManager.currentTransportType }
      )
      await viewModel.noteSessionReadiness()
      let beforeMutation = appState.radioWiFiSettingsRevision
      try await service.setRadioCLIValue("wifi.enabled", to: "0")
      try await waitUntil("accepted mutation should refresh WiFi observation") {
        appState.radioWiFiSettingsRevision == beforeMutation + 2
      }
      #expect(viewModel.rebootPending)

      let state = service.radioWiFiState
      let oldContextID = UUID()
      let observedAt = ContinuousClock.now
      let before = RadioWiFiResetObservation(uptimeSeconds: 1000, startedAt: observedAt, completedAt: observedAt)
      let candidate = try #require(state.prepareExpectedReboot(contextID: oldContextID, observation: before))
      state.completeRebootSubmission(id: candidate, succeeded: true)
      state.noteLinkEnded(contextID: oldContextID)
      await harness.bluetooth.setResponsePackets(
        for: .getStats, packets: [Data([ResponseCode.stats.rawValue, 0, 0x74, 0x0E, 1, 0, 0, 0, 0, 0, 0])]
      )
      let mutationRevision = state.revision
      let beforeClear = appState.radioWiFiSettingsRevision
      let staleOwnerCleared = await service.confirmExpectedRadioWiFiReboot(
        id: candidate, expectedRadioID: state.radioID, ownerIsCurrent: { false }
      )
      #expect(!staleOwnerCleared)
      #expect(state.rebootPending)
      #expect(appState.radioWiFiSettingsRevision == beforeClear)

      #expect(await service.confirmExpectedRadioWiFiReboot(
        id: candidate, expectedRadioID: state.radioID, ownerIsCurrent: { true }
      ))

      try await waitUntil("readback clear should refresh WiFi observation") {
        appState.radioWiFiSettingsRevision == beforeClear + 1
      }
      #expect(state.revision == mutationRevision)
      #expect(!viewModel.rebootPending)
    }
  }
}
