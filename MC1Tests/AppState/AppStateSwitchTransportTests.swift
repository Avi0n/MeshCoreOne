import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("AppState transport switch")
@MainActor
struct AppStateSwitchTransportTests {
  @Test
  func `a timeout shows the connection failed alert`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness { harness in
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

      #expect(harness.appState.connectionUI.showingConnectionFailedAlert)
      #expect(harness.appState.connectionUI.failedPairingDeviceID == nil)
      #expect(harness.appState.connectionLinkSwitch?.checked == .bluetooth)
      #expect(harness.appState.connectionLinkSwitch?.target == nil)
      #expect(await harness.wifi.connectCount == 1)
      #expect(harness.manager.currentTransportType == nil)
    }
  }

  @Test
  func `an authentication failure records the passed device`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness { harness in
      await harness.wifi.setConnectError(BLEError.authenticationFailed)

      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

      #expect(harness.appState.connectionUI.failedPairingDeviceID == harness.device.id)
      #expect(harness.appState.connectionLinkSwitch?.checked == .bluetooth)
      #expect(harness.appState.connectionLinkSwitch?.target == nil)
      #expect(await harness.wifi.connectCount == 1)
    }
  }
}
