import CoreBluetooth
import Foundation
@testable import MC1Services
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
  func `currentPhaseName returns idle when idle`() async {
    let sm = BLEStateMachine()
    let name = await sm.currentPhaseName
    #expect(name == "idle")
  }

  // MARK: - Handler Registration Tests

  @Test
  func `setDisconnectionHandler can be registered`() async {
    let sm = BLEStateMachine()

    await sm.setDisconnectionHandler { _, _ in }

    #expect(await sm.currentPhase.name == "idle")
  }

  @Test
  func `setReconnectionHandler can be registered`() async {
    let sm = BLEStateMachine()

    await sm.setReconnectionHandler { _, _ in }

    #expect(await sm.currentPhase.name == "idle")
  }

  @Test
  func `setBluetoothStateChangeHandler can be registered`() async {
    let sm = BLEStateMachine()

    await sm.setBluetoothStateChangeHandler { _ in }

    #expect(await sm.currentPhase.name == "idle")
  }

  @Test
  func `setAutoReconnectingHandler can be registered`() async {
    let sm = BLEStateMachine()

    await sm.setAutoReconnectingHandler { _, _ in }

    #expect(await sm.currentPhase.name == "idle")
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

  @Test
  func `handler replacement works correctly`() async {
    let sm = BLEStateMachine()

    await sm.setDisconnectionHandler { _, _ in }
    await sm.setDisconnectionHandler { _, _ in }

    // Multiple handler registrations should not crash
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
  func `extend predicate allows extension while connected and within budget`() {
    let shouldExtend = BLEStateMachine.shouldExtendDiscoveryTimeout(
      peripheralState: .connected,
      extensions: 0,
      maxExtensions: BLEStateMachine.maxDiscoveryTimeoutExtensions
    )

    #expect(shouldExtend) // link is up; a didConnect/discovery callback is in flight
  }

  @Test
  func `extend predicate rejects extension when peripheral is not connected`() {
    for state in [CBPeripheralState.connecting, .disconnected, .disconnecting] {
      let shouldExtend = BLEStateMachine.shouldExtendDiscoveryTimeout(
        peripheralState: state,
        extensions: 0,
        maxExtensions: BLEStateMachine.maxDiscoveryTimeoutExtensions
      )

      #expect(!shouldExtend, "state \(state.rawValue) should tear down, not extend")
    }
  }

  @Test
  func `extend predicate rejects extension once the budget is spent`() {
    let max = BLEStateMachine.maxDiscoveryTimeoutExtensions

    #expect(BLEStateMachine.shouldExtendDiscoveryTimeout(peripheralState: .connected, extensions: max - 1, maxExtensions: max))
    #expect(!BLEStateMachine.shouldExtendDiscoveryTimeout(peripheralState: .connected, extensions: max, maxExtensions: max))
  }

  @Test
  func `advancing the connection generation resets the discovery-extension budget`() async {
    let sm = BLEStateMachine()
    await sm.recordDiscoveryTimeoutExtension()
    await sm.recordDiscoveryTimeoutExtension()
    #expect(await sm.currentDiscoveryTimeoutExtensions == BLEStateMachine.maxDiscoveryTimeoutExtensions)

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
