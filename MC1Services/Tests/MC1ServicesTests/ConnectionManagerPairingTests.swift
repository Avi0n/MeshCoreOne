import Foundation
import os
import Testing
@testable import MC1Services

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

    @Test("unfavoritedNodeCount throws when not connected")
    func unfavoritedNodeCountThrowsWhenDisconnected() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()

        try await #expect {
            _ = try await manager.unfavoritedNodeCount()
        } throws: { error in
            guard let e = error as? ConnectionError, case .notConnected = e else { return false }
            return true
        }
    }

    @Test("removeUnfavoritedNodes throws when not connected")
    func removeUnfavoritedNodesThrowsWhenDisconnected() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()

        try await #expect {
            _ = try await manager.removeUnfavoritedNodes()
        } throws: { error in
            guard let e = error as? ConnectionError, case .notConnected = e else { return false }
            return true
        }
    }

    @Test("removeStaleNodes throws when not connected")
    func removeStaleNodesThrowsWhenDisconnected() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()

        try await #expect {
            _ = try await manager.removeStaleNodes(olderThanDays: 30)
        } throws: { error in
            guard let e = error as? ConnectionError, case .notConnected = e else { return false }
            return true
        }
    }

    @Test("pairNewDevice rejects re-entry without clearing the outer call's flag")
    func pairNewDeviceRejectsReEntry() async throws {
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

    @Test("pairNewDevice stops BLE scanning before showing ASK picker")
    func pairNewDeviceStopsBLEScanningBeforeShowingPicker() async throws {
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

        for await _ in pickerEntered.stream { break }

        #expect(await env.stateMachine.stopScanningCallCount == 1)
        #expect(await env.stateMachine.isScanning == false)

        pickerGate.continuation.finish()
        _ = await pairTask.result
    }

    // MARK: - Device Update Tests

    @Test("updateDevice(with:) updates connectedDevice")
    func updateDeviceWithDTO() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        let device = DeviceDTO.testDevice(nodeName: "NewDevice")

        manager.updateDevice(with: device)

        #expect(manager.connectedDevice?.nodeName == "NewDevice")
        #expect(manager.connectedDevice?.id == device.id)
    }

    @Test("updateAutoAddConfig updates config when connected")
    func updateAutoAddConfigWhenConnected() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        let device = DeviceDTO.testDevice()
        manager.updateDevice(with: device)

        manager.updateAutoAddConfig(AutoAddConfig(bitmask: 5, maxHops: 3))

        #expect(manager.connectedDevice?.autoAddConfig == 5)
        #expect(manager.connectedDevice?.autoAddMaxHops == 3)
    }

    @Test("updateAutoAddConfig does nothing when not connected")
    func updateAutoAddConfigWhenDisconnected() throws {
        let (manager, _) = try ConnectionManager.createForTesting()

        manager.updateAutoAddConfig(AutoAddConfig(bitmask: 5, maxHops: 3))

        #expect(manager.connectedDevice == nil)
    }

    @Test("updateClientRepeat updates repeat flag when connected")
    func updateClientRepeatWhenConnected() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        let device = DeviceDTO.testDevice()
        manager.updateDevice(with: device)

        manager.updateClientRepeat(true)

        #expect(manager.connectedDevice?.clientRepeat == true)
    }

    @Test("updatePathHashMode updates hash mode when connected")
    func updatePathHashModeWhenConnected() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        let device = DeviceDTO.testDevice()
        manager.updateDevice(with: device)

        manager.updatePathHashMode(2)

        #expect(manager.connectedDevice?.pathHashMode == 2)
    }

    // MARK: - Pre-Repeat Settings Tests

    @Test("savePreRepeatSettings changes connectedDevice")
    func savePreRepeatSettingsChangesDevice() throws {
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

    @Test("clearPreRepeatSettings clears saved settings")
    func clearPreRepeatSettingsClears() throws {
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

    @Test("waitForOtherAppReconnection returns true on immediate detection")
    func waitForOtherAppReconnectionImmediate() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        await mock.setStubbedIsDeviceConnectedToSystem(true)

        let result = await manager.waitForOtherAppReconnection(deviceID)

        #expect(result == true)
        let callCount = await mock.isDeviceConnectedToSystemCalls.count
        #expect(callCount == 1)
    }

    @Test("waitForOtherAppReconnection returns false after all checks")
    func waitForOtherAppReconnectionNoOtherApp() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        await mock.setStubbedIsDeviceConnectedToSystem(false)

        let result = await manager.waitForOtherAppReconnection(deviceID)

        #expect(result == false)
        let callCount = await mock.isDeviceConnectedToSystemCalls.count
        #expect(callCount == 6)
    }

    @Test("waitForOtherAppReconnection detects delayed reconnection")
    func waitForOtherAppReconnectionDelayed() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        // Return true on the 3rd call using a counter outside the actor
        let callCounter = Counter()
        await mock.setIsDeviceConnectedToSystemHandler { _ in
            return callCounter.increment() >= 3
        }

        let result = await manager.waitForOtherAppReconnection(deviceID)

        #expect(result == true)
        let callCount = await mock.isDeviceConnectedToSystemCalls.count
        #expect(callCount == 3)
    }

    // MARK: - Data Operations

    @Test("fetchSavedDevices returns empty array when no devices saved")
    func fetchSavedDevicesEmpty() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()

        let devices = try await manager.fetchSavedDevices()

        #expect(devices.isEmpty)
    }

    @Test("deleteDevice completes without error for non-existent device")
    func deleteDeviceNonExistent() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()

        try await manager.deleteDevice(id: UUID())
    }

    // MARK: - Forget/Resume Signal

    @Test("deleteDevice clears the persisted connection when removing the last-connected radio")
    func deleteDeviceClearsLastConnectedSignal() async throws {
        let suiteName = "test.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { UserDefaults().removePersistentDomain(forName: suiteName) }
        let (manager, _) = try ConnectionManager.createForTesting(defaults: defaults)

        let deviceID = UUID()
        manager.persistConnection(deviceID: deviceID, radioID: UUID(), deviceName: "Radio")
        #expect(manager.lastConnectedDeviceID == deviceID)

        try await manager.deleteDevice(id: deviceID)

        // The auto-reconnect / onboarding-resume signal must not survive deleting its own radio.
        #expect(manager.lastConnectedDeviceID == nil)
    }

    @Test("deleteDevice preserves the persisted connection when removing a different radio")
    func deleteDevicePreservesOtherLastConnectedSignal() async throws {
        let suiteName = "test.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { UserDefaults().removePersistentDomain(forName: suiteName) }
        let (manager, _) = try ConnectionManager.createForTesting(defaults: defaults)

        let lastConnected = UUID()
        manager.persistConnection(deviceID: lastConnected, radioID: UUID(), deviceName: "Radio")

        try await manager.deleteDevice(id: UUID())

        #expect(manager.lastConnectedDeviceID == lastConnected)
    }

    @Test("forgetDevice clears the persisted connection when removing the last-connected radio")
    func forgetDeviceClearsLastConnectedSignal() async throws {
        let suiteName = "test.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { UserDefaults().removePersistentDomain(forName: suiteName) }
        let (manager, _) = try ConnectionManager.createForTesting(defaults: defaults)

        let deviceID = UUID()
        manager.persistConnection(deviceID: deviceID, radioID: UUID(), deviceName: "Radio")
        #expect(manager.lastConnectedDeviceID == deviceID)

        await manager.forgetDevice(id: deviceID)

        // The auto-reconnect / onboarding-resume signal must not survive forgetting its own radio.
        #expect(manager.lastConnectedDeviceID == nil)
    }

    @Test("forgetDevice preserves the persisted connection when removing a different radio")
    func forgetDevicePreservesOtherLastConnectedSignal() async throws {
        let suiteName = "test.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { UserDefaults().removePersistentDomain(forName: suiteName) }
        let (manager, _) = try ConnectionManager.createForTesting(defaults: defaults)

        let lastConnected = UUID()
        manager.persistConnection(deviceID: lastConnected, radioID: UUID(), deviceName: "Radio")

        await manager.forgetDevice(id: UUID())

        #expect(manager.lastConnectedDeviceID == lastConnected)
    }
}
