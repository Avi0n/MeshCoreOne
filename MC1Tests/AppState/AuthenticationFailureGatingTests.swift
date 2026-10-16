import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// The "Couldn't Pair" alert must present only while the app is active. A bond
/// failure observed on a backgrounded auto-reconnect must not latch a stale
/// alert that then appears on the next foreground even after a good reconnect.
@Suite("Authentication Failure Gating")
@MainActor
struct AuthenticationFailureGatingTests {
  @Test
  func `an active app presents the pairing-failure alert`() {
    let appState = AppState()

    appState.handleAuthenticationFailure(deviceID: UUID(), isAppActive: true)

    #expect(appState.connectionUI.showingConnectionFailedAlert == true)
    #expect(appState.connectionUI.pairingFailureKind == .authentication)
  }

  @Test
  func `an inactive app suppresses the pairing-failure alert`() {
    let appState = AppState()

    appState.handleAuthenticationFailure(deviceID: UUID(), isAppActive: false)

    #expect(appState.connectionUI.showingConnectionFailedAlert == false)
    #expect(appState.connectionUI.pairingFailureKind == nil)
  }

  @Test
  func `clearing pairing failure resets every alert field`() {
    let sut = ConnectionUIState()
    sut.presentPairingFailure(.connectionFailed(deviceID: UUID(), underlying: BLEError.authenticationFailed))
    #expect(sut.showingConnectionFailedAlert == true)

    sut.clearPairingFailure()

    #expect(sut.showingConnectionFailedAlert == false)
    #expect(sut.connectionFailedTitle == nil)
    #expect(sut.connectionFailedMessage == nil)
    #expect(sut.pairingFailureKind == nil)
    #expect(sut.failedPairingDeviceID == nil)
  }
}
