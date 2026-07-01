import Foundation
@testable import MC1Services
import os
import Testing

/// Thread-safe counter for tracking call counts in mock handlers.
private final class Counter: Sendable {
  private let lock = OSAllocatedUnfairLock(initialState: 0)

  /// Increments the counter and returns the new value.
  func increment() -> Int {
    lock.withLock { value in
      value += 1
      return value
    }
  }
}

@Suite("ConnectionManager Pairing Tests")
@MainActor
struct ConnectionManagerPairingTests {
  // MARK: - State Guard Tests

  @Test
  func `unfavoritedNodeCount throws when not connected`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    try await #expect {
      _ = try await manager.unfavoritedNodeCount()
    } throws: { error in
      guard let e = error as? ConnectionError, case .notConnected = e else { return false }
      return true
    }
  }

  @Test
  func `removeUnfavoritedNodes throws when not connected`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    try await #expect {
      _ = try await manager.removeUnfavoritedNodes()
    } throws: { error in
      guard let e = error as? ConnectionError, case .notConnected = e else { return false }
      return true
    }
  }

  @Test
  func `removeStaleNodes throws when not connected`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    try await #expect {
      _ = try await manager.removeStaleNodes(olderThanDays: 30)
    } throws: { error in
      guard let e = error as? ConnectionError, case .notConnected = e else { return false }
      return true
    }
  }

  @Test
  func `pairNewDevice rejects re-entry without clearing the outer call's flag`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    // Simulate the outer call having already entered pairNewDevice and
    // suspended in showPicker.
    manager.setTestState(isPairingInProgress: true)

    try await #expect {
      try await manager.pairNewDevice()
    } throws: { error in
      guard let e = error as? DevicePairingError, case .alreadyInProgress = e else { return false }
      return true
    }

    // The inner call's defer must not unwind the outer call's state.
    #expect(manager.isPairingInProgress == true)
  }

  @Test
  func `pairNewDevice stops BLE scanning before showing ASK picker`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    let stream = env.manager.startBLEScanning()
    let scanConsumer = Task {
      for await _ in stream {}
    }
    defer { scanConsumer.cancel() }

    try await waitUntil("BLE scanning should start") {
      await env.stateMachine.isScanning
    }

    let pickerEntered = AsyncStream<Void>.makeStream()
    let pickerGate = AsyncStream<Void>.makeStream()
    env.accessorySetupKit.pickerEnteredSignal = pickerEntered.continuation
    env.accessorySetupKit.pickerGate = pickerGate.stream
    env.accessorySetupKit.setPickerResult(.failure(AccessorySetupKitError.pickerDismissed))

    let pairTask = Task {
      try? await env.manager.pairNewDevice()
    }

    for await _ in pickerEntered.stream {
      break
    }

    #expect(await env.stateMachine.stopScanningCallCount == 1)
    #expect(await env.stateMachine.isScanning == false)

    pickerGate.continuation.finish()
    _ = await pairTask.result
  }

  // MARK: - Device Update Tests

  @Test
  func `updateDevice(with:) updates connectedDevice`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let device = DeviceDTO.testDevice(nodeName: "NewDevice")

    manager.updateDevice(with: device)

    #expect(manager.connectedDevice?.nodeName == "NewDevice")
    #expect(manager.connectedDevice?.id == device.id)
  }

  @Test
  func `updateAutoAddConfig updates config when connected`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let device = DeviceDTO.testDevice()
    manager.updateDevice(with: device)

    manager.updateAutoAddConfig(AutoAddConfig(bitmask: 5, maxHops: 3))

    #expect(manager.connectedDevice?.autoAddConfig == 5)
    #expect(manager.connectedDevice?.autoAddMaxHops == 3)
  }

  @Test
  func `updateAutoAddConfig does nothing when not connected`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.updateAutoAddConfig(AutoAddConfig(bitmask: 5, maxHops: 3))

    #expect(manager.connectedDevice == nil)
  }

  @Test
  func `updateClientRepeat updates repeat flag when connected`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let device = DeviceDTO.testDevice()
    manager.updateDevice(with: device)

    manager.updateClientRepeat(true)

    #expect(manager.connectedDevice?.clientRepeat == true)
  }

  @Test
  func `updatePathHashMode updates hash mode when connected`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let device = DeviceDTO.testDevice()
    manager.updateDevice(with: device)

    manager.updatePathHashMode(2)

    #expect(manager.connectedDevice?.pathHashMode == 2)
  }

  // MARK: - Pre-Repeat Settings Tests

  @Test
  func `savePreRepeatSettings changes connectedDevice`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let device = DeviceDTO.testDevice(
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20
    )
    manager.updateDevice(with: device)
    let original = manager.connectedDevice

    manager.savePreRepeatSettings()

    #expect(manager.connectedDevice != original)
    #expect(manager.connectedDevice != nil)
  }

  @Test
  func `clearPreRepeatSettings clears saved settings`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let device = DeviceDTO.testDevice()
    manager.updateDevice(with: device)

    manager.savePreRepeatSettings()
    let afterSave = manager.connectedDevice

    manager.clearPreRepeatSettings()
    let afterClear = manager.connectedDevice

    #expect(afterSave != afterClear)
  }

  // MARK: - Other-App Reconnection Polling

  @Test
  func `waitForOtherAppReconnection returns true on immediate detection`() async throws {
    let (manager, mock) = try ConnectionManager.createForTesting()
    let deviceID = UUID()

    await mock.setStubbedIsDeviceConnectedToSystem(true)

    let result = await manager.waitForOtherAppReconnection(deviceID)

    #expect(result == true)
    let callCount = await mock.isDeviceConnectedToSystemCalls.count
    #expect(callCount == 1)
  }

  @Test
  func `waitForOtherAppReconnection returns false after all checks`() async throws {
    let (manager, mock) = try ConnectionManager.createForTesting()
    let deviceID = UUID()

    await mock.setStubbedIsDeviceConnectedToSystem(false)

    let result = await manager.waitForOtherAppReconnection(deviceID)

    #expect(result == false)
    let callCount = await mock.isDeviceConnectedToSystemCalls.count
    #expect(callCount == 6)
  }

  @Test
  func `waitForOtherAppReconnection detects delayed reconnection`() async throws {
    let (manager, mock) = try ConnectionManager.createForTesting()
    let deviceID = UUID()

    // Return true on the 3rd call using a counter outside the actor
    let callCounter = Counter()
    await mock.setIsDeviceConnectedToSystemHandler { _ in
      callCounter.increment() >= 3
    }

    let result = await manager.waitForOtherAppReconnection(deviceID)

    #expect(result == true)
    let callCount = await mock.isDeviceConnectedToSystemCalls.count
    #expect(callCount == 3)
  }

  // MARK: - Data Operations

  @Test
  func `fetchSavedDevices returns empty array when no devices saved`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    let devices = try await manager.fetchSavedDevices()

    #expect(devices.isEmpty)
  }

  @Test
  func `deleteDevice completes without error for non-existent device`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    try await manager.deleteDevice(id: UUID())
  }

  // MARK: - Forget/Resume Signal

  @Test
  func `deleteDevice clears the persisted connection when removing the last-connected radio`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }
    let (manager, _) = try ConnectionManager.createForTesting(defaults: defaults)

    let deviceID = UUID()
    manager.persistConnection(deviceID: deviceID, radioID: UUID(), deviceName: "Radio")
    #expect(manager.lastConnectedDeviceID == deviceID)

    try await manager.deleteDevice(id: deviceID)

    // The auto-reconnect / onboarding-resume signal must not survive deleting its own radio.
    #expect(manager.lastConnectedDeviceID == nil)
  }

  @Test
  func `deleteDevice preserves the persisted connection when removing a different radio`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }
    let (manager, _) = try ConnectionManager.createForTesting(defaults: defaults)

    let lastConnected = UUID()
    manager.persistConnection(deviceID: lastConnected, radioID: UUID(), deviceName: "Radio")

    try await manager.deleteDevice(id: UUID())

    #expect(manager.lastConnectedDeviceID == lastConnected)
  }

  @Test
  func `forgetDevice clears the persisted connection when removing the last-connected radio`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }
    let (manager, _) = try ConnectionManager.createForTesting(defaults: defaults)

    let deviceID = UUID()
    manager.persistConnection(deviceID: deviceID, radioID: UUID(), deviceName: "Radio")
    #expect(manager.lastConnectedDeviceID == deviceID)

    await manager.forgetDevice(id: deviceID)

    // The auto-reconnect / onboarding-resume signal must not survive forgetting its own radio.
    #expect(manager.lastConnectedDeviceID == nil)
  }

  @Test
  func `forgetDevice preserves the persisted connection when removing a different radio`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }
    let (manager, _) = try ConnectionManager.createForTesting(defaults: defaults)

    let lastConnected = UUID()
    manager.persistConnection(deviceID: lastConnected, radioID: UUID(), deviceName: "Radio")

    await manager.forgetDevice(id: UUID())

    #expect(manager.lastConnectedDeviceID == lastConnected)
  }
}
