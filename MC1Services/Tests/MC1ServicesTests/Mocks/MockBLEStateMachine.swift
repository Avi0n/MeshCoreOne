@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services

/// Mock BLE state machine for testing ConnectionManager.
/// Uses actor for thread-safe mutable state access.
public actor MockBLEStateMachine: BLEStateMachineProtocol {
  // MARK: - Stubs

  public var stubbedIsConnected: Bool = false
  public var stubbedIsAutoReconnecting: Bool = false
  public var stubbedConnectedDeviceID: UUID?
  public var stubbedLinkDiagnostics = BLELinkDiagnostics(
    centralState: "poweredOn", phase: .idle, peripheralState: nil
  )
  public var stubbedIsBluetoothPoweredOff: Bool = false
  public var stubbedIsDeviceConnectedToSystem: Bool = false
  public var isDeviceConnectedToSystemHandler: (@Sendable (UUID) -> Bool)?
  public var stubbedDidStartAdoptingSystemConnectedPeripheral: Bool = false

  // MARK: - Protocol Properties

  public var isConnected: Bool {
    stubbedIsConnected
  }

  public var isAutoReconnecting: Bool {
    stubbedIsAutoReconnecting
  }

  public var connectedDeviceID: UUID? {
    stubbedConnectedDeviceID
  }

  public var linkDiagnostics: BLELinkDiagnostics {
    stubbedLinkDiagnostics
  }

  public var isBluetoothPoweredOff: Bool {
    stubbedIsBluetoothPoweredOff
  }

  public var hasAutoReconnectingHandler: Bool {
    autoReconnectingHandler != nil
  }

  // MARK: - Recorded Invocations

  public private(set) var activateCallCount = 0
  public private(set) var isDeviceConnectedToSystemCalls: [UUID] = []
  public private(set) var startAdoptingSystemConnectedPeripheralCalls: [UUID] = []
  public private(set) var startScanningCallCount = 0
  public private(set) var stopScanningCallCount = 0
  public private(set) var isScanning = false

  // MARK: - Captured Handlers

  private var autoReconnectingHandler: (@Sendable (UUID, String) -> Void)?
  private var bluetoothPoweredOnHandler: (@Sendable () -> Void)?
  private var bluetoothStateChangeHandler: (@Sendable (CBManagerState) -> Void)?
  private var deviceDiscoveredHandler: (@Sendable (UUID, String?, Int) -> Void)?
  public private(set) var recordedBondVerifications: [UUID: Date] = [:]
  public private(set) var appSessionLiveDeviceID: UUID?

  // MARK: - Initialization

  public init() {}

  // MARK: - Protocol Methods

  public func isDeviceConnectedToSystem(_ deviceID: UUID) -> Bool {
    isDeviceConnectedToSystemCalls.append(deviceID)
    if let handler = isDeviceConnectedToSystemHandler {
      return handler(deviceID)
    }
    return stubbedIsDeviceConnectedToSystem
  }

  public func systemConnectedPeripheralIDs() -> [UUID] {
    []
  }

  public func startAdoptingSystemConnectedPeripheral(_ deviceID: UUID) -> Bool {
    startAdoptingSystemConnectedPeripheralCalls.append(deviceID)
    return stubbedDidStartAdoptingSystemConnectedPeripheral
  }

  public func activate() {
    activateCallCount += 1
  }

  public func setAutoReconnectingHandler(_ handler: @escaping @Sendable (UUID, String) -> Void) {
    autoReconnectingHandler = handler
  }

  public func setBluetoothPoweredOnHandler(_ handler: @escaping @Sendable () -> Void) {
    bluetoothPoweredOnHandler = handler
  }

  public func recordBondVerification(deviceID: UUID, at date: Date) {
    recordedBondVerifications[deviceID] = date
  }

  public func clearBondVerification(deviceID: UUID) {
    recordedBondVerifications[deviceID] = nil
  }

  public func setAppSessionLive(deviceID: UUID?) {
    appSessionLiveDeviceID = deviceID
  }

  public func hasBondVerification(deviceID: UUID) -> Bool {
    recordedBondVerifications[deviceID] != nil
  }

  public func isAppSessionLive(deviceID: UUID) -> Bool {
    appSessionLiveDeviceID == deviceID
  }

  public func shouldPersistBondRefresh(deviceID: UUID) -> Bool {
    recordedBondVerifications[deviceID] != nil && appSessionLiveDeviceID == deviceID
  }

  public func setBondRefreshedHandler(_ handler: (@Sendable (UUID) -> Void)?) {
    // Empty stub — RSSI bond-refresh tests use the real BLEStateMachine actor.
  }

  public func setBluetoothStateChangeHandler(_ handler: @escaping @Sendable (CBManagerState) -> Void) {
    bluetoothStateChangeHandler = handler
  }

  public func setDeviceDiscoveredHandler(_ handler: @escaping @Sendable (UUID, String?, Int) -> Void) {
    deviceDiscoveredHandler = handler
  }

  public func startScanning() {
    startScanningCallCount += 1
    isScanning = true
  }

  public func stopScanning() {
    stopScanningCallCount += 1
    isScanning = false
  }

  public func setWritePacingDelay(_ delay: TimeInterval) {
    // No-op for testing
  }

  public private(set) var shutdownCallCount = 0
  public private(set) var appDidEnterBackgroundCallCount = 0
  public private(set) var appDidBecomeActiveCallCount = 0

  public func shutdown() {
    shutdownCallCount += 1
  }

  public func appDidEnterBackground() {
    appDidEnterBackgroundCallCount += 1
  }

  public func appDidBecomeActive() {
    appDidBecomeActiveCallCount += 1
  }

  // MARK: - Test Helpers

  /// Resets all stubs and recorded invocations
  public func reset() {
    stubbedIsConnected = false
    stubbedIsAutoReconnecting = false
    stubbedConnectedDeviceID = nil
    stubbedLinkDiagnostics = BLELinkDiagnostics(
      centralState: "poweredOn", phase: .idle, peripheralState: nil
    )
    stubbedIsBluetoothPoweredOff = false
    stubbedIsDeviceConnectedToSystem = false
    isDeviceConnectedToSystemHandler = nil
    stubbedDidStartAdoptingSystemConnectedPeripheral = false
    activateCallCount = 0
    isDeviceConnectedToSystemCalls = []
    startAdoptingSystemConnectedPeripheralCalls = []
    startScanningCallCount = 0
    stopScanningCallCount = 0
    isScanning = false
    appDidEnterBackgroundCallCount = 0
    appDidBecomeActiveCallCount = 0
    autoReconnectingHandler = nil
    bluetoothPoweredOnHandler = nil
    bluetoothStateChangeHandler = nil
    deviceDiscoveredHandler = nil
    recordedBondVerifications = [:]
    appSessionLiveDeviceID = nil
  }

  /// Simulates auto-reconnecting event
  public func simulateAutoReconnecting(deviceID: UUID, errorInfo: String = "none") {
    autoReconnectingHandler?(deviceID, errorInfo)
  }

  /// Simulates Bluetooth powered on event
  public func simulateBluetoothPoweredOn() {
    bluetoothPoweredOnHandler?()
  }

  /// Simulates a Bluetooth state change event
  public func simulateBluetoothStateChange(_ state: CBManagerState) {
    bluetoothStateChangeHandler?(state)
  }

  /// Simulates BLE discovery callback while scanning.
  public func simulateDiscoveredDevice(id: UUID, name: String? = nil, rssi: Int) {
    deviceDiscoveredHandler?(id, name, rssi)
  }
}

// MARK: - Stubbed Property Setters

extension MockBLEStateMachine {
  func setStubbedIsConnected(_ value: Bool) {
    stubbedIsConnected = value
  }

  func setStubbedIsAutoReconnecting(_ value: Bool) {
    stubbedIsAutoReconnecting = value
  }

  func setStubbedIsDeviceConnectedToSystem(_ value: Bool) {
    stubbedIsDeviceConnectedToSystem = value
  }

  func setIsDeviceConnectedToSystemHandler(_ handler: sending (@Sendable (UUID) -> Bool)?) {
    isDeviceConnectedToSystemHandler = handler
  }

  func setStubbedIsBluetoothPoweredOff(_ value: Bool) {
    stubbedIsBluetoothPoweredOff = value
  }

  func setStubbedDidStartAdoptingSystemConnectedPeripheral(_ value: Bool) {
    stubbedDidStartAdoptingSystemConnectedPeripheral = value
  }

  func setStubbedPhase(_ value: BLEPhaseKind) {
    stubbedLinkDiagnostics = BLELinkDiagnostics(
      centralState: stubbedLinkDiagnostics.centralState,
      phase: value,
      peripheralState: stubbedLinkDiagnostics.peripheralState
    )
  }

  func setStubbedConnectedDeviceID(_ value: UUID?) {
    stubbedConnectedDeviceID = value
  }
}
