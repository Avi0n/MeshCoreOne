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
