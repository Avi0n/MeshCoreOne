import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// Covers the routing a fresh BLE pairing attempt takes when it throws. A
/// rejected PIN during first-time pairing must surface copy naming the PIN and
/// warning about the system removal prompt, distinct from an established radio's
/// dead-bond recovery.
@Suite("Fresh Pairing Failure Routing")
@MainActor
struct FreshPairingFailureRoutingTests {
  @Test
  func `rejected PIN surfaces the PIN-rejected recovery`() {
    let sut = ConnectionUIState()
    let deviceID = UUID()

    sut.presentFreshPairingFailure(.connectionFailed(deviceID: deviceID, underlying: BLEError.authenticationFailed))

    #expect(sut.pairingFailureKind == .pinRejected)
    #expect(sut.failedPairingDeviceID == deviceID)
    #expect(sut.connectionFailedTitle == L10n.Localizable.Alert.PairingFailed.title)
    #expect(sut.connectionFailedMessage == L10n.Onboarding.DeviceScan.Error.pinRejected)
    #expect(sut.showingConnectionFailedAlert == true)
    #expect(sut.otherAppWarningDeviceID == nil)
  }

  @Test
  func `saved-device authentication failure keeps the dead-bond recovery`() {
    let sut = ConnectionUIState()
    let deviceID = UUID()

    sut.presentSavedDeviceConnectFailure(deviceID: deviceID, error: BLEError.authenticationFailed)

    #expect(sut.pairingFailureKind == .authentication)
    #expect(sut.connectionFailedMessage == L10n.Onboarding.DeviceScan.Error.authenticationFailed)
    #expect(sut.connectionFailedTitle == L10n.Localizable.Alert.PairingFailed.title)
  }

  @Test
  func `transient fresh-pair failure keeps the non-destructive retry`() {
    let sut = ConnectionUIState()
    let deviceID = UUID()

    sut.presentFreshPairingFailure(.connectionFailed(deviceID: deviceID, underlying: BLEError.connectionTimeout))

    #expect(sut.pairingFailureKind == .transient)
    #expect(sut.failedPairingDeviceID == deviceID)
    #expect(sut.connectionFailedTitle == nil)
    #expect(sut.connectionFailedMessage == L10n.Onboarding.DeviceScan.Error.connectionFailed)
    #expect(sut.showingConnectionFailedAlert == true)
  }

  @Test
  func `fresh-pair other-app failure routes to the other-app warning`() {
    let sut = ConnectionUIState()
    let deviceID = UUID()

    sut.presentFreshPairingFailure(.deviceConnectedToOtherApp(deviceID: deviceID))

    #expect(sut.otherAppWarningDeviceID == deviceID)
    #expect(sut.pairingFailureKind == nil)
    #expect(sut.showingConnectionFailedAlert == false)
  }
}
