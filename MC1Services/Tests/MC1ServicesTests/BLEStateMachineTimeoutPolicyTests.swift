import CoreBluetooth
@testable import MC1Services
import Testing

/// Truth table for the auto-reconnect discovery watchdog. A peripheral that is
/// not `.connected` is backed by an OS pending connect that never expires, so
/// the watchdog waits (regardless of the extension budget); only a
/// connected-but-wedged discovery consumes extensions and eventually tears down.
@Suite("BLEStateMachine Auto-Reconnect Timeout Policy Tests")
struct BLEStateMachineTimeoutPolicyTests {
  private let maxExtensions = BLEStateMachine.maxDiscoveryTimeoutExtensions

  @Test
  func `disconnected peripheral waits for the pending connect`() {
    #expect(
      BLEStateMachine.autoReconnectTimeoutAction(
        peripheralState: .disconnected, extensions: 0, maxExtensions: maxExtensions
      ) == .waitForPendingConnect
    )
  }

  @Test
  func `connecting peripheral waits for the pending connect`() {
    #expect(
      BLEStateMachine.autoReconnectTimeoutAction(
        peripheralState: .connecting, extensions: 0, maxExtensions: maxExtensions
      ) == .waitForPendingConnect
    )
  }

  @Test
  func `waiting never exhausts into teardown while the link is down`() {
    #expect(
      BLEStateMachine.autoReconnectTimeoutAction(
        peripheralState: .disconnected, extensions: maxExtensions, maxExtensions: maxExtensions
      ) == .waitForPendingConnect
    )
  }

  @Test
  func `connected peripheral with budget extends the discovery window`() {
    #expect(
      BLEStateMachine.autoReconnectTimeoutAction(
        peripheralState: .connected, extensions: 0, maxExtensions: maxExtensions
      ) == .extendWindow
    )
  }

  @Test
  func `connected peripheral with exhausted budget tears down`() {
    #expect(
      BLEStateMachine.autoReconnectTimeoutAction(
        peripheralState: .connected, extensions: maxExtensions, maxExtensions: maxExtensions
      ) == .tearDown
    )
  }
}

/// Truth table for classifying a discovery/subscribe watchdog teardown. Only a
/// peripheral that reached `.connected` yet exhausted its extension budget is
/// treated as a silently invalidated bond and escalated to
/// `authenticationFailed`; every other teardown state is a plain timeout.
@Suite("BLEStateMachine Discovery Timeout Classification Tests")
struct BLEStateMachineDiscoveryTimeoutClassificationTests {
  private let maxExtensions = BLEStateMachine.maxDiscoveryTimeoutExtensions

  private func isAuthenticationFailed(_ error: BLEError) -> Bool {
    if case .authenticationFailed = error { return true }
    return false
  }

  private func isConnectionTimeout(_ error: BLEError) -> Bool {
    if case .connectionTimeout = error { return true }
    return false
  }

  @Test
  func `connected peripheral with a spent budget escalates to an auth failure`() {
    #expect(
      isAuthenticationFailed(
        BLEStateMachine.discoveryTimeoutError(
          peripheralState: .connected, extensions: maxExtensions, maxExtensions: maxExtensions
        )
      )
    )
  }

  @Test
  func `connected peripheral within budget is a plain connection timeout`() {
    #expect(
      isConnectionTimeout(
        BLEStateMachine.discoveryTimeoutError(
          peripheralState: .connected, extensions: 0, maxExtensions: maxExtensions
        )
      )
    )
  }

  @Test
  func `a link that never reached connected is a plain connection timeout`() {
    for state in [CBPeripheralState.disconnected, .connecting, .disconnecting] {
      #expect(
        isConnectionTimeout(
          BLEStateMachine.discoveryTimeoutError(
            peripheralState: state, extensions: maxExtensions, maxExtensions: maxExtensions
          )
        ),
        "state \(state.rawValue) should be a connection timeout, not an auth failure"
      )
    }
  }
}
