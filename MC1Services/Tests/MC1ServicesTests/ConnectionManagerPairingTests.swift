#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services
@testable import MeshCore
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

  @Test
  func `updateDevice stamps appliedRadioPresetID from the event`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.updateDevice(with: DeviceDTO.testDevice().copy {
      $0.appliedRadioPresetID = nil
      $0.clientRepeat = false
    })
    let br = try #require(RadioPresets.all.first { $0.id == "br" })
    manager.updateDevice(from: selfInfo(from: br), appliedRadioPresetID: "br")
    #expect(manager.connectedDevice?.appliedRadioPresetID == "br")
  }

  @Test
  func `updateDevice clears appliedRadioPresetID when RF no longer matches`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.updateDevice(with: DeviceDTO.testDevice().copy {
      $0.appliedRadioPresetID = "br"
      $0.clientRepeat = false
    })
    let us = try #require(RadioPresets.all.first { $0.id == "us-ca" })
    manager.updateDevice(from: selfInfo(from: us), appliedRadioPresetID: nil)
    #expect(manager.connectedDevice?.appliedRadioPresetID == nil)
  }

  @Test
  func `updateDevice keeps appliedRadioPresetID when RF still matches`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.updateDevice(with: DeviceDTO.testDevice().copy {
      $0.appliedRadioPresetID = "br"
      $0.clientRepeat = false
    })
    let br = try #require(RadioPresets.all.first { $0.id == "br" })
    manager.updateDevice(from: selfInfo(from: br), appliedRadioPresetID: nil)
    #expect(manager.connectedDevice?.appliedRadioPresetID == "br")
  }

  @Test
  func `updateDevice keeps appliedRadioPresetID during Repeat Mode`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.updateDevice(with: DeviceDTO.testDevice().copy {
      $0.appliedRadioPresetID = "br"
      $0.clientRepeat = false
    })
    manager.updateClientRepeat(true)
    manager.updateDevice(
      from: selfInfo(frequencyMHz: 869.495, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8),
      appliedRadioPresetID: nil
    )
    #expect(manager.connectedDevice?.appliedRadioPresetID == "br")
  }

  private func selfInfo(from preset: RadioPreset) -> MeshCore.SelfInfo {
    selfInfo(
      frequencyMHz: preset.frequencyMHz,
      bandwidthKHz: preset.bandwidthKHz,
      spreadingFactor: preset.spreadingFactor,
      codingRate: preset.codingRate
    )
  }

  private func selfInfo(
    frequencyMHz: Double,
    bandwidthKHz: Double,
    spreadingFactor: UInt8,
    codingRate: UInt8
  ) -> MeshCore.SelfInfo {
    MeshCore.SelfInfo(
      advertisementType: 0,
      txPower: 20,
      maxTxPower: 20,
      publicKey: Data(repeating: 0x01, count: 32),
      latitude: 0,
      longitude: 0,
      multiAcks: 2,
      advertisementLocationPolicy: 0,
      telemetryModeEnvironment: 0,
      telemetryModeLocation: 0,
      telemetryModeBase: 2,
      manualAddContacts: false,
      radioFrequency: frequencyMHz,
      radioBandwidth: bandwidthKHz,
      radioSpreadingFactor: spreadingFactor,
      radioCodingRate: codingRate,
      name: "TestNode"
    )
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

  @Test
  func `radio erase records before pairing and resumes after local failure`() async throws {
    enum InjectedFailure: Error { case save }

    let environment = try ConnectionManager.createForPairingTesting()
    defer { environment.cleanup() }
    let manager = environment.manager
    let pairing = environment.accessorySetupKit
    let store = manager.persistenceStore
    let radio = DeviceDTO.testDevice()
    try await store.saveDevice(radio)
    pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: radio.id, displayName: radio.nodeName)])
    let link = ReceiveScope.Lifetime()
    let captured = try #require(await store.captureReceiveScope(radioID: radio.radioID, link: link))

    await store.setRadioEraseFaults(begin: { throw InjectedFailure.save }, complete: nil)
    await #expect(throws: InjectedFailure.save) { try await manager.startRadioErase(radio) }
    #expect(pairing.removeAccessoryCallCount == 0)
    #expect(captured.isCurrent)
    #expect(try await store.fetchDevice(id: radio.id) != nil)

    await store.setRadioEraseFaults(begin: nil, complete: nil)
    pairing.removeAccessoryError = DevicePairingError.cancelled
    await #expect(throws: DevicePairingError.cancelled) { try await manager.startRadioErase(radio) }
    #expect(pairing.accessory(for: radio.id) != nil)
    #expect(try await store.fetchDevice(id: radio.id) != nil)
    #expect(!captured.isCurrent)
    #expect(try await store.fetchPendingRadioErases().isEmpty)
    #expect(try await store.captureReceiveScope(radioID: radio.radioID, link: link)?.isCurrent == true)

    pairing.removeAccessoryError = nil
    await store.setRadioEraseFaults(begin: nil, complete: { throw InjectedFailure.save })
    await #expect(throws: InjectedFailure.save) { try await manager.startRadioErase(radio) }
    #expect(pairing.accessory(for: radio.id) == nil)
    #expect(manager.radioCleanup[radio.radioID]?.state == .failed)
    let target = try #require(await store.fetchPendingRadioErases().first)

    manager.retryRadioErase(target)
    _ = await manager.radioEraseTasks[radio.radioID]?.result
    #expect(manager.radioCleanup[radio.radioID]?.state == .failed)
    #expect(try await store.fetchPendingRadioErases().count == 1)

    await store.setRadioEraseFaults(begin: nil, complete: nil)
    manager.retryRadioErase(target)
    _ = await manager.radioEraseTasks[radio.radioID]?.result
    #expect(manager.radioCleanup[radio.radioID]?.state == .completed)
    #expect(pairing.removeAccessoryCallCount == 2)
    #expect(try await store.fetchPendingRadioErases().isEmpty)
    #expect(try await store.fetchDevice(id: radio.id) == nil)
  }

  @Test(arguments: [false, true])
  func `factory reset retry only repeats local cleanup`(cancelPairingRemoval: Bool) async throws {
    enum InjectedFailure: Error { case save }

    let environment = try ConnectionManager.createForPairingTesting()
    defer { environment.cleanup() }
    let manager = environment.manager
    let store = manager.persistenceStore
    let radio = DeviceDTO.testDevice()
    try await store.saveDevice(radio)
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    try await startEraseTestSession(session, transport: transport, radio: radio)
    let services = ServiceContainer(session: session, dataStore: store, radioID: radio.radioID)
    manager.setTestState(
      connectionState: .connected, services: .some(services), session: .some(session), connectedDevice: .some(radio)
    )

    if cancelPairingRemoval {
      environment.accessorySetupKit.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: radio.id, displayName: radio.nodeName)
      ])
      environment.accessorySetupKit.removeAccessoryError = DevicePairingError.cancelled
    } else {
      await store.setRadioEraseFaults(begin: nil, complete: { throw InjectedFailure.save })
    }
    await transport.holdNextSend()
    let context = try #require(manager.captureCurrentRemoval())
    let reset = Task { try await manager.factoryResetAndEraseRadio(context) }
    try await waitUntil("factory reset reaches transport") { await transport.sendIsWaiting }
    #expect(try await store.fetchPendingRadioErases().first?.radioID == radio.radioID)
    #expect(await Array(transport.sentData.dropFirst()) == [PacketBuilder.factoryReset()])
    await transport.failHeldSend()
    await transport.releaseHeldSend()
    if cancelPairingRemoval {
      await #expect(throws: DevicePairingError.cancelled) { try await reset.value }
      #expect(manager.radioCleanup[radio.radioID]?.state == .failed)
      #expect(try await store.fetchDevice(id: radio.id) != nil)
    } else {
      await #expect(throws: InjectedFailure.save) { try await reset.value }
    }
    let target = try #require(await store.fetchPendingRadioErases().first)

    environment.accessorySetupKit.removeAccessoryError = nil
    await store.setRadioEraseFaults(begin: nil, complete: nil)
    manager.retryRadioErase(target)
    _ = await manager.radioEraseTasks[radio.radioID]?.result
    #expect(manager.radioCleanup[radio.radioID]?.state == .completed)
    #expect(await Array(transport.sentData.dropFirst()) == [PacketBuilder.factoryReset()])
  }

  @Test
  func `cancelled erase settles in flight message and restarts queued delivery`() async throws {
    let environment = try ConnectionManager.createForPairingTesting()
    defer { environment.cleanup() }
    let manager = environment.manager
    let pairing = environment.accessorySetupKit
    let store = manager.persistenceStore
    let radio = DeviceDTO.testDevice()
    try await store.saveDevice(radio)
    pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: radio.id, displayName: radio.nodeName)])
    let (gate, answerPrompt) = AsyncStream<Void>.makeStream()
    defer { answerPrompt.finish() }
    pairing.removeAccessoryGate = gate
    pairing.removeAccessoryError = DevicePairingError.cancelled

    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    try await startEraseTestSession(session, transport: transport, radio: radio)
    let services = ServiceContainer(session: session, dataStore: store, radioID: radio.radioID)
    manager.setTestState(
      connectionState: .connected, services: .some(services), session: .some(session), connectedDevice: .some(radio)
    )
    let poller = services.messagePollingService
    await services.syncCoordinator.wireMessageHandlers(dependencies: services.syncDependencies, radioID: radio.radioID)
    await poller.startAutoFetch(radioID: radio.radioID)
    try await waitUntil("poller and automatic fetch are subscribed") { await session.subscriberCountForTest >= 2 }

    let getMessage = PacketBuilder.getMessage()
    let timestamp = UInt32(Date().timeIntervalSince1970)
    await transport.simulateReceive(Data([ResponseCode.messagesWaiting.rawValue]))
    try await waitUntil("first queue read reaches transport") { await transport.sentData.last == getMessage }
    let erase = Task { try await manager.startRadioErase(radio) }
    try await waitUntil("erase suspends new queue reads") { await session.isMessageFetchingSuspended }
    #expect(pairing.removeAccessoryCallCount == 0)
    #expect(try await !store.isRadioErasePending(radio.radioID))

    await transport.simulateReceive(eraseTestChannelFrame("before prompt", timestamp: timestamp))
    try await waitUntil("pairing removal reaches confirmation") {
      await MainActor.run { pairing.removeAccessoryCallCount == 1 }
    }
    #expect(try await store.fetchMessages(radioID: radio.radioID, channelIndex: 0).map(\.text) == ["before prompt"])
    #expect(try await store.isRadioErasePending(radio.radioID))
    await transport.simulateReceive(Data([ResponseCode.messagesWaiting.rawValue]))
    try await waitUntil("message waiting was observed during pairing confirmation") {
      await session.processedMessagesWaitingCountForTest == 2
    }
    #expect(await transport.sentData.filter { $0 == getMessage }.count == 1)

    answerPrompt.yield()
    await #expect(throws: DevicePairingError.cancelled) { try await erase.value }
    #expect(try await !store.isRadioErasePending(radio.radioID))
    try await waitUntil("cancellation restarts the radio queue drain") {
      await transport.sentData.filter { $0 == getMessage }.count == 2
    }
    await transport.simulateReceive(eraseTestChannelFrame("during prompt", timestamp: timestamp + 1))
    try await waitUntil("radio queue is checked after the resumed delivery") {
      await transport.sentData.filter { $0 == getMessage }.count == 3
    }
    await transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))
    try await waitUntil("both channel arrivals are stored once") {
      let messages = try? await store.fetchMessages(radioID: radio.radioID, channelIndex: 0)
      return messages?.map(\.text).sorted() == ["before prompt", "during prompt"]
    }
    await poller.stopAutoFetch()
    await session.stop()
  }

  private func startEraseTestSession(_ session: MeshCoreSession, transport: MockTransport, radio: DeviceDTO) async throws {
    let start = Task { try await session.start() }
    try await waitUntil("app start reaches transport") { await !transport.sentData.isEmpty }
    var selfInfo = Data([ResponseCode.selfInfo.rawValue, ContactType.chat.rawValue, 22, 22])
    selfInfo.append(radio.publicKey)
    selfInfo.append(Data(repeating: 0, count: 12))
    for value in [radio.frequency, radio.bandwidth] {
      selfInfo.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
    }
    selfInfo.append(contentsOf: [radio.spreadingFactor, radio.codingRate])
    selfInfo.append(contentsOf: radio.nodeName.utf8)
    await transport.simulateReceive(selfInfo)
    try await start.value
  }

  private func eraseTestChannelFrame(_ body: String, timestamp: UInt32) -> Data {
    var frame = Data([ResponseCode.channelMessageReceivedV3.rawValue, 32, 0, 0, 0, 0, 0])
    frame.append(withUnsafeBytes(of: timestamp.littleEndian) { Data($0) })
    frame.append(contentsOf: "Peer: \(body)".utf8)
    return frame
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

    let device = DeviceDTO.testDevice()
    try await manager.persistenceStore.saveDevice(device)
    manager.setTestState(connectedDevice: .some(device))
    manager.persistConnection(deviceID: device.id, radioID: device.radioID, deviceName: device.nodeName)
    #expect(manager.lastConnectedDeviceID == device.id)

    let context = try #require(manager.captureCurrentRemoval())
    _ = try await manager.forgetDevice(context, deleteData: true)

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

    let device = DeviceDTO.testDevice()
    try await manager.persistenceStore.saveDevice(device)
    try await manager.startRadioErase(device)

    #expect(manager.lastConnectedDeviceID == lastConnected)
  }
}
