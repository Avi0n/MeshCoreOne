import CoreBluetooth
import Foundation
@testable import MC1Services
import ObjectiveC
import Testing

@Suite("BLEStateMachine Tests")
struct BLEStateMachineTests {
  // MARK: - Initial State Tests

  @Test
  func `initializes in idle phase`() async {
    let sm = BLEStateMachine()
    let phase = await sm.currentPhase
    #expect(phase.name == "idle")
  }

  @Test
  func `isConnected returns false when idle`() async {
    let sm = BLEStateMachine()
    let connected = await sm.isConnected
    #expect(connected == false)
  }

  @Test
  func `connectedDeviceID returns nil when idle`() async {
    let sm = BLEStateMachine()
    let deviceID = await sm.connectedDeviceID
    #expect(deviceID == nil)
  }

  @Test
  func `isAutoReconnecting returns false when idle`() async {
    let sm = BLEStateMachine()
    let reconnecting = await sm.isAutoReconnecting
    #expect(reconnecting == false)
  }

  @Test
  func `linkDiagnostics reports the idle phase when idle`() async {
    let sm = BLEStateMachine()
    let diagnostics = await sm.linkDiagnostics
    #expect(diagnostics.phase == .idle)
  }

  // MARK: - Disconnect Tests

  @Test
  func `disconnect returns immediately when idle`() async {
    let sm = BLEStateMachine()

    await sm.disconnect()

    #expect(await sm.currentPhase.name == "idle")
    #expect(await sm.isConnected == false)
  }

  // MARK: - Connection Error Tests

  @Test
  func `connect throws appropriate error for unknown UUID`() async throws {
    let sm = BLEStateMachine()
    let unknownID = UUID()

    await sm.activate()

    await #expect(throws: BLEError.self) {
      _ = try await sm.connect(to: unknownID)
    }
  }

  @Test
  func `send throws notConnected when idle`() async throws {
    let sm = BLEStateMachine()
    let testData = Data([0x01, 0x02, 0x03])

    do {
      try await sm.send(testData)
      Issue.record("Expected notConnected error")
    } catch let error as BLEError {
      if case .notConnected = error {
        // Expected
      } else {
        Issue.record("Expected notConnected error, got \(error)")
      }
    }
  }

  // MARK: - Idempotency Tests

  @Test
  func `disconnect is idempotent`() async {
    let sm = BLEStateMachine()

    // Multiple disconnects should not crash
    await sm.disconnect()
    await sm.disconnect()
    await sm.disconnect()

    #expect(await sm.currentPhase.name == "idle")
  }

  @Test
  func `activate is idempotent`() async {
    let sm = BLEStateMachine()

    // Multiple activations should not crash or create duplicate managers
    await sm.activate()
    await sm.activate()
    await sm.activate()

    #expect(await sm.currentPhase.name == "idle")
  }

  // MARK: - Connection Generation Tests

  @Test
  func `connection generation starts at zero`() async {
    let sm = BLEStateMachine()
    let generation = await sm.currentConnectionGeneration
    #expect(generation == 0)
  }

  @Test
  func `disconnect callback from previous generation is rejected`() {
    let timestamp: CFAbsoluteTime = 98
    let generationStart: CFAbsoluteTime = 101

    let isStale = BLEStateMachine.isDisconnectCallbackFromPreviousGeneration(
      timestamp: timestamp,
      generationStart: generationStart
    )

    #expect(isStale) // 98 + 1.0 = 99 < 101 → stale
  }

  @Test
  func `disconnect callback at tolerance boundary is accepted`() {
    let generationStart: CFAbsoluteTime = 200
    let timestamp = generationStart - 1.0

    let isStale = BLEStateMachine.isDisconnectCallbackFromPreviousGeneration(
      timestamp: timestamp,
      generationStart: generationStart
    )

    #expect(!isStale) // 199 + 1.0 = 200, not < 200 → accepted
  }

  @Test
  func `disconnect callback beyond tolerance is rejected`() {
    let generationStart: CFAbsoluteTime = 200
    let timestamp = generationStart - 1.5

    let isStale = BLEStateMachine.isDisconnectCallbackFromPreviousGeneration(
      timestamp: timestamp,
      generationStart: generationStart
    )

    #expect(isStale) // 198.5 + 1.0 = 199.5 < 200 → stale
  }

  // MARK: - Discovery Timeout Extension Tests

  @Test
  func `advancing the connection generation resets the discovery-extension budget`() async {
    let sm = BLEStateMachine()
    await sm.primeDiscoveryExtensions(ReconnectPolicy.maxDiscoveryTimeoutExtensions)
    #expect(await sm.currentDiscoveryTimeoutExtensions == ReconnectPolicy.maxDiscoveryTimeoutExtensions)

    await sm.advanceConnectionGeneration()

    #expect(await sm.currentDiscoveryTimeoutExtensions == 0)
  }

  @Test
  func `disconnect callback is accepted when timestamp is at or after generation start`() {
    let generationStart: CFAbsoluteTime = 500

    let atStart = BLEStateMachine.isDisconnectCallbackFromPreviousGeneration(
      timestamp: generationStart,
      generationStart: generationStart
    )
    let afterStart = BLEStateMachine.isDisconnectCallbackFromPreviousGeneration(
      timestamp: generationStart + 300,
      generationStart: generationStart
    )

    #expect(!atStart)
    #expect(!afterStart)
  }
}

// MARK: - Discovery-timeout watchdog end-to-end coverage

/// A large timeout so a re-armed discovery watchdog cannot fire during the test window.
private let watchdogNonFiringTimeout: TimeInterval = 3600

/// A brief timeout so a re-armed discovery watchdog fires on its own during the test window.
private let watchdogReArmFiringTimeout: TimeInterval = 0.02

/// Bounds the wait for the re-armed watchdog so a dropped re-arm fails fast instead of hanging.
private let watchdogReArmObservationWindow: TimeInterval = 5

/// Retains mock peripherals for the process lifetime. `CBPeripheral` has no
/// public initializer and its `-dealloc` touches internals that a runtime-
/// allocated instance never set up, so releasing one crashes; never freeing
/// them keeps the doubles usable.
private enum WatchdogPeripheralStore {
  nonisolated(unsafe) static var retained: [CBPeripheral] = []
}

/// A `CBPeripheral` double whose `state` is forced to `.connected`.
private final class ConnectedTestPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID()
  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .connected
  }
}

/// A `CBPeripheral` double whose `state` is forced to `.disconnected`.
private final class DisconnectedTestPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID()
  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .disconnected
  }
}

/// Allocates a mock peripheral without invoking `CBPeripheral`'s unavailable
/// initializer and keeps it alive so it is never deallocated.
private func makeLeakedPeripheral<T: CBPeripheral>(_ type: T.Type) -> T {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(type, 0) as! T
  WatchdogPeripheralStore.retained.append(peripheral)
  return peripheral
}

/// Carries the discovery-phase continuation across the actor boundary and records
/// how the state machine ultimately resumed it (or that it was left suspended).
private final class WatchdogContinuationBox: @unchecked Sendable {
  var continuation: CheckedContinuation<Void, Error>?
  var outcome: Result<Void, Error>?
  var isResumed: Bool {
    outcome != nil
  }
}

/// Actor-isolated test seams that install the exact state
/// `armServiceDiscoveryTimeout` would produce, then drive the real handler.
extension BLEStateMachine {
  func injectTestCentralManager() {
    centralManager = CBCentralManager(delegate: nil, queue: nil)
  }

  func primeDiscoveryExtensions(_ count: Int) {
    reconnectPolicy.discoveryTimeoutExtensions = count
  }

  fileprivate func primeDiscoveringServices(peripheral: CBPeripheral, box: WatchdogContinuationBox, extensionsUsed: Int) {
    guard let continuation = box.continuation else { return }
    phase = .discoveringServices(peripheral: peripheral, continuation: continuation)
    phaseStartTime = Date()
    reconnectPolicy.discoveryTimeoutExtensions = extensionsUsed
    serviceDiscoveryTimeoutTask = Task {}
  }

  func fireServiceDiscoveryTimeout(for peripheral: CBPeripheral) {
    handleServiceDiscoveryTimeout(for: peripheral)
  }

  func cancelServiceDiscoveryTimeoutForTesting() {
    serviceDiscoveryTimeoutTask?.cancel()
    serviceDiscoveryTimeoutTask = nil
  }
}

@Suite("BLEStateMachine service-discovery watchdog")
struct BLEStateMachineDiscoveryWatchdogTests {
  /// Spins up a suspended `CheckedContinuation` and returns a box holding it plus the
  /// task awaiting it, so the discovery phase can own a live continuation without the
  /// test blocking on it.
  private func suspendedContinuation() async -> (WatchdogContinuationBox, Task<Void, Never>) {
    let box = WatchdogContinuationBox()
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

  private func isConnectionTimeout(_ outcome: Result<Void, Error>?) -> Bool {
    guard case let .failure(error) = outcome,
          let bleError = error as? BLEError,
          case .connectionTimeout = bleError else { return false }
    return true
  }

  private func isAuthenticationFailed(_ outcome: Result<Void, Error>?) -> Bool {
    guard case let .failure(error) = outcome,
          let bleError = error as? BLEError,
          case .authenticationFailed = bleError else { return false }
    return true
  }

  /// Polls until the discovery continuation is resumed or the window elapses, so a
  /// re-arm that never fired fails the assertion instead of suspending the test.
  private func awaitResumed(_ box: WatchdogContinuationBox, within timeout: TimeInterval) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while !box.isResumed {
      if Date() > deadline { return false }
      try? await Task.sleep(for: .milliseconds(5))
    }
    return true
  }

  @Test
  func `connected peripheral mid-discovery extends the window instead of tearing down`() async {
    let sm = BLEStateMachine(serviceDiscoveryTimeout: watchdogNonFiringTimeout)
    await sm.injectTestCentralManager()
    let peripheral = makeLeakedPeripheral(ConnectedTestPeripheral.self)
    let (box, driver) = await suspendedContinuation()

    await sm.primeDiscoveringServices(peripheral: peripheral, box: box, extensionsUsed: 0)
    await sm.fireServiceDiscoveryTimeout(for: peripheral)

    // The live link survives: the phase stays in discovery, the extension budget
    // is consumed, and the discovery continuation is not failed.
    let phaseName = await sm.currentPhase.name
    #expect(phaseName == "discoveringServices")
    #expect(await sm.currentDiscoveryTimeoutExtensions == 1)
    #expect(!box.isResumed)

    // Release the still-suspended continuation and the re-armed watchdog. The
    // phase distinguishes the branch taken, so the continuation is resumed
    // exactly once: the extend branch leaves it suspended here, while a
    // teardown branch already resumed it.
    if phaseName == "discoveringServices" {
      box.continuation?.resume()
    }
    await driver.value
    await sm.cancelServiceDiscoveryTimeoutForTesting()
  }

  /// A peripheral still `.connected` after the extension budget is spent never
  /// delivered a discovery callback and no CoreBluetooth error arrived: the
  /// strongest in-app signal of a silently invalidated bond. The teardown
  /// escalates to `authenticationFailed` so it reaches guided re-pair recovery
  /// rather than looping generic timeout retries against the dead bond.
  @Test
  func `connected peripheral with a spent budget escalates to an auth failure`() async {
    let sm = BLEStateMachine(serviceDiscoveryTimeout: watchdogNonFiringTimeout)
    await sm.injectTestCentralManager()
    let peripheral = makeLeakedPeripheral(ConnectedTestPeripheral.self)
    let (box, driver) = await suspendedContinuation()

    await sm.primeDiscoveringServices(
      peripheral: peripheral,
      box: box,
      extensionsUsed: ReconnectPolicy.maxDiscoveryTimeoutExtensions
    )
    await sm.fireServiceDiscoveryTimeout(for: peripheral)
    await driver.value

    #expect(await sm.currentPhase.name == "idle")
    // The extension budget stays bounded: it is never pushed past its ceiling.
    #expect(await sm.currentDiscoveryTimeoutExtensions == ReconnectPolicy.maxDiscoveryTimeoutExtensions)
    #expect(isAuthenticationFailed(box.outcome))
  }

  /// The extend branch must re-arm the watchdog, not merely consume a budget unit.
  /// The manual fire spends the final extension, so the re-armed watchdog is the only
  /// thing that can fire again; when it does, the spent budget forces a teardown.
  /// Dropping the re-arm leaves the continuation suspended, caught here as a missed teardown.
  @Test
  func `extending re-arms the watchdog so a later timeout still tears the link down`() async {
    let sm = BLEStateMachine(serviceDiscoveryTimeout: watchdogReArmFiringTimeout)
    await sm.injectTestCentralManager()
    let peripheral = makeLeakedPeripheral(ConnectedTestPeripheral.self)
    let (box, driver) = await suspendedContinuation()

    await sm.primeDiscoveringServices(
      peripheral: peripheral,
      box: box,
      extensionsUsed: ReconnectPolicy.maxDiscoveryTimeoutExtensions - 1
    )
    await sm.fireServiceDiscoveryTimeout(for: peripheral)

    let tornDown = await awaitResumed(box, within: watchdogReArmObservationWindow)
    #expect(tornDown, "Extending must re-arm the watchdog; without the re-arm the link keeps a spent budget and no live timeout")

    if tornDown {
      #expect(await sm.currentPhase.name == "idle")
      #expect(await sm.currentDiscoveryTimeoutExtensions == ReconnectPolicy.maxDiscoveryTimeoutExtensions)
      #expect(isAuthenticationFailed(box.outcome))
    } else {
      // Release the still-suspended continuation so the driver task can finish.
      box.continuation?.resume()
    }
    await driver.value
    await sm.cancelServiceDiscoveryTimeoutForTesting()
  }

  @Test
  func `disconnected peripheral tears down instead of extending`() async {
    let sm = BLEStateMachine(serviceDiscoveryTimeout: watchdogNonFiringTimeout)
    await sm.injectTestCentralManager()
    let peripheral = makeLeakedPeripheral(DisconnectedTestPeripheral.self)
    let (box, driver) = await suspendedContinuation()

    await sm.primeDiscoveringServices(peripheral: peripheral, box: box, extensionsUsed: 0)
    await sm.fireServiceDiscoveryTimeout(for: peripheral)
    await driver.value

    #expect(await sm.currentPhase.name == "idle")
    #expect(await sm.currentDiscoveryTimeoutExtensions == 0)
    #expect(isConnectionTimeout(box.outcome))
  }
}
