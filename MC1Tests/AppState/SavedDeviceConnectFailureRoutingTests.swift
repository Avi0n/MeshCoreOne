import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// Covers the routing a user-initiated connect to an already-paired radio takes
/// when the attempt throws. A dead bond surfaces as `authenticationFailed`, which
/// must reach the guided re-pair recovery, not the OK-only "check your PIN" alert.
@Suite("Saved Device Connect Failure Routing")
@MainActor
struct SavedDeviceConnectFailureRoutingTests {
  @Test
  func `authentication failure surfaces guided re-pair recovery`() {
    let sut = ConnectionUIState()
    let deviceID = UUID()

    sut.presentSavedDeviceConnectFailure(deviceID: deviceID, error: BLEError.authenticationFailed)

    #expect(sut.failedPairingDeviceID == deviceID)
    #expect(sut.pairingFailureKind == .authentication)
    #expect(sut.connectionFailedTitle == L10n.Localizable.Alert.PairingFailed.title)
    #expect(sut.connectionFailedMessage == L10n.Onboarding.DeviceScan.Error.authenticationFailed)
    #expect(sut.showingConnectionFailedAlert == true)
    #expect(sut.otherAppWarningDeviceID == nil)
  }

  @Test
  func `other-app failure routes to the other-app warning`() {
    let sut = ConnectionUIState()
    let deviceID = UUID()

    sut.presentSavedDeviceConnectFailure(deviceID: deviceID, error: BLEError.deviceConnectedToOtherApp)

    #expect(sut.otherAppWarningDeviceID == deviceID)
    #expect(sut.showingConnectionFailedAlert == false)
    #expect(sut.failedPairingDeviceID == nil)
    #expect(sut.pairingFailureKind == nil)
  }

  @Test
  func `non-auth failure routes to the generic connection-failed alert`() {
    let sut = ConnectionUIState()

    sut.presentSavedDeviceConnectFailure(deviceID: UUID(), error: BLEError.connectionTimeout)

    #expect(sut.showingConnectionFailedAlert == true)
    #expect(sut.failedPairingDeviceID == nil)
    #expect(sut.pairingFailureKind == nil)
    #expect(sut.connectionFailedTitle == nil)
    #expect(sut.otherAppWarningDeviceID == nil)
  }

  @Test
  func `generic failure clears a stale failed-pairing device id`() {
    let sut = ConnectionUIState()
    sut.presentPairingFailure(.connectionFailed(deviceID: UUID(), underlying: BLEError.authenticationFailed))
    #expect(sut.failedPairingDeviceID != nil)

    sut.presentSavedDeviceConnectFailure(deviceID: UUID(), error: BLEError.connectionTimeout)

    #expect(sut.failedPairingDeviceID == nil)
    #expect(sut.pairingFailureKind == nil)
  }
}
