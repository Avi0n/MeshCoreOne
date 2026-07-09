import CoreBluetooth
import Foundation
@testable import MC1Services
import ObjectiveC
import Testing

/// Covers phase-continuation safety around state restoration and teardown:
/// a connect parked in `.waitingForBluetooth` must never be clobbered without
/// resuming, an `isReconnecting` disconnect with no owned peripheral must not
/// resurrect `.autoReconnecting`, a `.discoveryComplete` teardown must preserve
/// its error classification for the in-flight `connect()`, and `shutdown()`
/// must cancel the RSSI keepalive it bypasses `cleanupPhaseResources` for.
@Suite("BLEStateMachine restoration and teardown safety")
struct BLEStateMachineRestorationAndTeardownTests {
  private var bondInvalidationError: NSError {
    NSError(domain: CBErrorDomain, code: CBError.peerRemovedPairingInformation.rawValue)
  }

  /// Spins up a suspended `CheckedContinuation` so a phase can own a live
  /// continuation without the test blocking on it.
  private func suspendedContinuation() async -> (ContinuationBox, Task<Void, Never>) {
    let box = ContinuationBox()
    let driver = Task {
      do {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
          box.continuation = continuation
        }
        box.outcome = .success(())
      } catch {
        box.outcome = .failure(error)
      }
    }
    while box.continuation == nil {
      await Task.yield()
    }
    return (box, driver)
  }

  private func isConnectionFailed(_ outcome: Result<Void, Error>?) -> Bool {
    guard case let .failure(error) = outcome,
          let bleError = error as? BLEError,
          case .connectionFailed = bleError else { return false }
    return true
  }

  // MARK: - State restoration vs .waitingForBluetooth

  @Test
  func `willRestoreState resumes a parked waitingForBluetooth continuation before claiming the machine`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(RestorationDisconnectedPeripheral.self)
    let (box, driver) = await suspendedContinuation()
    await sm.primeWaitingForBluetooth(box: box)

    await sm.handleWillRestoreState(peripheral)
    await driver.value

    #expect(isConnectionFailed(box.outcome))
    #expect(await sm.currentPhase.name == "restoringState")
  }

  @Test
  func `failPendingBluetoothWait is a no-op outside waitingForBluetooth`() async {
    let sm = BLEStateMachine()

    await sm.failPendingBluetoothWait(reason: "test")

    #expect(await sm.currentPhase.name == "idle")
  }

  // MARK: - isReconnecting disconnect with no owned peripheral

  @Test
  func `isReconnecting disconnect in idle does not resurrect autoReconnecting`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(RestorationDisconnectedPeripheral.self)

    await sm.handleDidDisconnect(
      peripheral,
      timestamp: CFAbsoluteTimeGetCurrent(),
      isReconnecting: true,
      error: nil
    )

    #expect(await sm.currentPhase.name == "idle")
    #expect(await sm.isAutoReconnecting == false)
  }

  @Test
  func `isReconnecting disconnect in waitingForBluetooth resumes the parked continuation instead of leaking it`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(RestorationDisconnectedPeripheral.self)
    let (box, driver) = await suspendedContinuation()
    await sm.primeWaitingForBluetooth(box: box)

    await sm.handleDidDisconnect(
      peripheral,
      timestamp: CFAbsoluteTimeGetCurrent(),
      isReconnecting: true,
      error: nil
    )
    await driver.value

    #expect(box.isResumed)
    #expect(await sm.currentPhase.name == "idle")
    #expect(await sm.isAutoReconnecting == false)
  }

  // MARK: - .discoveryComplete teardown classification

  @Test
  func `discoveryComplete teardown records the bond-loss classification for the in-flight connect`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(RestorationDisconnectedPeripheral.self)
    await sm.primeDiscoveryComplete(peripheral: peripheral)

    await sm.handleDidDisconnect(
      peripheral,
      timestamp: CFAbsoluteTimeGetCurrent(),
      isReconnecting: true,
      error: bondInvalidationError
    )

    #expect(await sm.currentPhase.name == "idle")
    let recorded = await sm.discoveryCompleteTeardownError
    guard case .authenticationFailed = recorded else {
      Issue.record("Expected recorded authenticationFailed, got \(String(describing: recorded))")
      return
    }
  }

  @Test
  func `advancing the connection generation clears a stale discoveryComplete teardown error`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(RestorationDisconnectedPeripheral.self)
    await sm.primeDiscoveryComplete(peripheral: peripheral)
    await sm.handleDidDisconnect(
      peripheral,
      timestamp: CFAbsoluteTimeGetCurrent(),
      isReconnecting: true,
      error: bondInvalidationError
    )
    #expect(await sm.discoveryCompleteTeardownError != nil)

    await sm.advanceConnectionGeneration()

    #expect(await sm.discoveryCompleteTeardownError == nil)
  }

  // MARK: - shutdown() keepalive

  @Test
  func `shutdown cancels the RSSI keepalive that its direct phase write bypasses`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(RestorationConnectedPeripheral.self)
    await sm.primeConnectedWithKeepalive(peripheral: peripheral)
    #expect(await sm.isRSSIKeepaliveActive)

    await sm.shutdown()

    #expect(await sm.isRSSIKeepaliveActive == false)
    #expect(await sm.currentPhase.name == "idle")
  }

  // MARK: - Write-ACK sequence fencing and classification

  @Test
  func `stale didWriteValue callback is dropped instead of resuming the newer write`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(RestorationConnectedPeripheral.self)
    let (box, driver) = await suspendedContinuation()
    await sm.primePendingWrite(box: box, sequence: 7)

    await sm.handleDidWriteValue(peripheral, characteristic: makeTxCharacteristic(), error: nil, writeSequence: 6)

    #expect(!box.isResumed)

    // The matching callback still completes the write normally.
    await sm.handleDidWriteValue(peripheral, characteristic: makeTxCharacteristic(), error: nil, writeSequence: 7)
    await driver.value
    guard case .success = box.outcome else {
      Issue.record("Expected the matching callback to resume the write successfully")
      return
    }
  }

  @Test
  func `write error carrying a bond-invalidation code surfaces as authenticationFailed`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(RestorationConnectedPeripheral.self)
    let (box, driver) = await suspendedContinuation()
    await sm.primePendingWrite(box: box, sequence: 1)

    await sm.handleDidWriteValue(
      peripheral,
      characteristic: makeTxCharacteristic(),
      error: NSError(domain: CBATTErrorDomain, code: CBATTError.insufficientEncryption.rawValue),
      writeSequence: 1
    )
    await driver.value

    guard case let .failure(error) = box.outcome,
          case .authenticationFailed = error as? BLEError else {
      Issue.record("Expected authenticationFailed, got \(String(describing: box.outcome))")
      return
    }
  }

  @Test
  func `write error without an auth code stays a writeError`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(RestorationConnectedPeripheral.self)
    let (box, driver) = await suspendedContinuation()
    await sm.primePendingWrite(box: box, sequence: 1)

    await sm.handleDidWriteValue(
      peripheral,
      characteristic: makeTxCharacteristic(),
      error: NSError(domain: CBATTErrorDomain, code: CBATTError.writeNotPermitted.rawValue),
      writeSequence: 1
    )
    await driver.value

    guard case let .failure(error) = box.outcome,
          case .writeError = error as? BLEError else {
      Issue.record("Expected writeError, got \(String(describing: box.outcome))")
      return
    }
  }

  private func makeTxCharacteristic() -> CBMutableCharacteristic {
    CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic),
      properties: [.write],
      value: nil,
      permissions: [.writeable]
    )
  }
}

// MARK: - Test doubles and seams

/// Carries a phase continuation across the actor boundary and records how the
/// state machine ultimately resumed it (or that it was left suspended).
private final class ContinuationBox: @unchecked Sendable {
  var continuation: CheckedContinuation<Void, Error>?
  var outcome: Result<Void, Error>?
  var isResumed: Bool {
    outcome != nil
  }
}

/// Retains mock peripherals for the process lifetime. `CBPeripheral` has no
/// public initializer and its `-dealloc` touches internals that a runtime-
/// allocated instance never set up, so releasing one crashes; never freeing
/// them keeps the doubles usable.
private enum RestorationPeripheralStore {
  nonisolated(unsafe) static var retained: [CBPeripheral] = []
}

/// A `CBPeripheral` double with a stable identity and `.disconnected` state.
private final class RestorationDisconnectedPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID()
  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .disconnected
  }
}

/// A `CBPeripheral` double with a stable identity and `.connected` state.
private final class RestorationConnectedPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID()
  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .connected
  }
}

/// Allocates a mock peripheral without invoking `CBPeripheral`'s unavailable
/// initializer and keeps it alive so it is never deallocated.
private func makeLeakedPeripheral<T: CBPeripheral>(_ type: T.Type) -> T {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(type, 0) as! T
  RestorationPeripheralStore.retained.append(peripheral)
  return peripheral
}

/// Actor-isolated seams that install the phases these handlers switch on,
/// then let tests drive the real handlers.
private extension BLEStateMachine {
  func primeWaitingForBluetooth(box: ContinuationBox) {
    guard let continuation = box.continuation else { return }
    phase = .waitingForBluetooth(continuation: continuation)
    phaseStartTime = Date()
  }

  func primeDiscoveryComplete(peripheral: CBPeripheral) {
    let tx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic),
      properties: [.write],
      value: nil,
      permissions: [.writeable]
    )
    let rx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.rxCharacteristic),
      properties: [.notify],
      value: nil,
      permissions: [.readable]
    )
    phase = .discoveryComplete(peripheral: peripheral, tx: tx, rx: rx)
    phaseStartTime = Date()
  }

  func primeConnectedWithKeepalive(peripheral: CBPeripheral) {
    let tx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic),
      properties: [.write],
      value: nil,
      permissions: [.writeable]
    )
    let rx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.rxCharacteristic),
      properties: [.notify],
      value: nil,
      permissions: [.readable]
    )
    let (_, continuation) = AsyncStream.makeStream(of: Data.self)
    phase = .connected(peripheral: peripheral, tx: tx, rx: rx, dataContinuation: continuation)
    phaseStartTime = Date()
    startRSSIKeepalive(for: peripheral)
  }

  func primePendingWrite(box: ContinuationBox, sequence: UInt64) {
    guard let continuation = box.continuation else { return }
    pendingWriteContinuation = continuation
    pendingWriteSequence = sequence
  }
}
