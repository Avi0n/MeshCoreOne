import CoreBluetooth
import Foundation
@testable import MC1Services
import Testing

@Suite("BLEStateMachine.makeConnectionError")
struct BLEStateMachineErrorMappingTests {
  @Test
  func `CBATT auth/encryption codes map to BLEError.authenticationFailed`() {
    let authCodes = [
      CBATTError.insufficientAuthentication.rawValue,
      CBATTError.insufficientAuthorization.rawValue,
      CBATTError.insufficientEncryption.rawValue,
      CBATTError.insufficientEncryptionKeySize.rawValue
    ]

    for code in authCodes {
      let nsError = NSError(domain: CBATTErrorDomain, code: code)
      let result = BLEStateMachine.makeConnectionError(nsError)
      guard case BLEError.authenticationFailed = result else {
        Issue.record("Expected .authenticationFailed for CBATTError code \(code), got \(result)")
        continue
      }
    }
  }

  @Test
  func `CBError.encryptionTimedOut maps to .connectionFailed, not authenticationFailed`() {
    // A single encryption timeout is transient (a backgrounded auto-reconnect
    // races iOS re-establishing the bond); only a definitive auth code, or a
    // majority of an exhausted retry budget, means a truly invalidated bond.
    let nsError = NSError(domain: CBErrorDomain, code: CBError.encryptionTimedOut.rawValue)
    let result = BLEStateMachine.makeConnectionError(nsError)
    guard case BLEError.connectionFailed = result else {
      Issue.record("Expected .connectionFailed, got \(result)")
      return
    }
  }

  @Test
  func `CBError.peerRemovedPairingInformation maps to BLEError.authenticationFailed`() {
    let nsError = NSError(domain: CBErrorDomain, code: CBError.peerRemovedPairingInformation.rawValue)
    let result = BLEStateMachine.makeConnectionError(nsError)
    guard case BLEError.authenticationFailed = result else {
      Issue.record("Expected .authenticationFailed, got \(result)")
      return
    }
  }

  @Test
  func `Non-auth CBATT codes fall through to .connectionFailed`() {
    let nsError = NSError(
      domain: CBATTErrorDomain,
      code: CBATTError.requestNotSupported.rawValue,
      userInfo: [NSLocalizedDescriptionKey: "Request not supported"]
    )
    let result = BLEStateMachine.makeConnectionError(nsError)
    guard case let BLEError.connectionFailed(msg) = result else {
      Issue.record("Expected .connectionFailed, got \(result)")
      return
    }
    #expect(msg == "Request not supported")
  }

  @Test
  func `Detection survives a localized description`() {
    // Simulate iOS localizing the auth-failure description into German.
    let nsError = NSError(
      domain: CBATTErrorDomain,
      code: CBATTError.insufficientAuthentication.rawValue,
      userInfo: [NSLocalizedDescriptionKey: "Authentifizierung ist unzureichend."]
    )
    let result = BLEStateMachine.makeConnectionError(nsError)
    guard case BLEError.authenticationFailed = result else {
      Issue.record("Expected .authenticationFailed for localized German auth error, got \(result)")
      return
    }
  }

  @Test
  func `nil error uses fallback message`() {
    let result = BLEStateMachine.makeConnectionError(nil, fallback: "Disconnected during setup")
    guard case let BLEError.connectionFailed(msg) = result else {
      Issue.record("Expected .connectionFailed, got \(result)")
      return
    }
    #expect(msg == "Disconnected during setup")
  }
}
