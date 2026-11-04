import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Connection recovery ownership")
@MainActor
struct ConnectionManagerRecoveryOwnershipTests {
  @Test(arguments: [false, true])
  func `queued wi fi recovery cannot join successor retirement`(disconnect: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let entry = BLEReconnectionTestHarness.Gate()
    var entered = false
    var completed = false
    _ = try await env.connect(.wifi)
    let requestID = env.manager.connectionRequestID
    env.manager.testWiFiRecoveryEntryHandler = { entered = true; await entry.wait() }
    await env.wifi.simulateRemoteLoss()
    try await waitUntil("socket loss must queue its actual recovery worker") { entered }
    let recovery = try #require(env.manager.wifiReconnectTask)
    let replacement = Task {
      if disconnect {
        _ = await env.manager.disconnect(reason: .userInitiated)
      } else {
        _ = try? await env.connect(.bluetooth)
      }
      completed = true
    }
    try await waitUntil("the explicit action must supersede queued recovery") {
      env.manager.connectionRequestID != requestID
    }
    entry.open()
    do {
      try await waitUntil("actual successor retirement must join cancelled recovery and finish") { completed }
    } catch {
      // A dependency cycle cannot join its own tasks; a failed assertion leaves
      // those tasks for the isolated test process to dispose.
      Issue.record("Successor retirement did not finish after the queued recovery was cancelled")
      return
    }
    await replacement.value
    await recovery.value
    #expect(env.manager.connectionTeardownCount == 0)
    #expect(await env.wifi.connectCount == 1, "obsolete recovery must never dial")
    if disconnect { _ = try await env.connect(.bluetooth) }
    #expect(try await env.manager.session?.getTime() != nil)
    #expect(await env.ble.sentData.contains { $0.first == CommandCode.appStart.rawValue })
    env.manager.testWiFiRecoveryEntryHandler = nil
    await env.close()
  }

  @Test(arguments: [false, true])
  func `foreground cannot replace admitted BLE target`(holdHandshake: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    var exchange: Task<Date, Error>?
    var replacement: Task<ConnectionResult, Error>?
    var foreground: Task<Void, Never>?
    var foregroundFinished = false
    do {
      _ = try await env.connect(.bluetooth)
      let previousID = env.deviceID
      let nextID = UUID()
      let saved = try #require(env.manager.connectedDevice)
      try await env.manager.persistenceStore.saveDevice(saved.copy { $0.id = nextID; $0.isActive = false })
      if holdHandshake {
        await env.ble.holdReplies(for: .appStart)
      } else {
        await env.ble.holdReplies(for: .getTime)
        let session = try #require(env.manager.session)
        exchange = Task { try await session.getTime() }
        try await waitUntil("A must submit the exchange being drained") { await env.ble.timeReads >= 2 }
      }
      let previousRequest = env.manager.connectionRequestID
      replacement = Task { try await env.manager.connect(to: nextID, forceFullSync: true, forceReconnect: true) }
      try await waitUntil("B must be admitted") { env.manager.connectionRequestID != previousRequest }
      let nextRequest = env.manager.connectionRequestID
      if holdHandshake {
        try await waitUntil("B must physically connect and submit appStart") {
          await env.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count == 2
        }
      } else {
        #expect(env.manager.connectionTeardownCount > 0)
      }
      #expect(env.manager.lastConnectedDeviceID == previousID)
      await env.stateMachine.setStubbedIsConnected(true)
      await env.stateMachine.setStubbedConnectedDeviceID(holdHandshake ? nextID : previousID)
      foreground = Task { await env.manager.appDidBecomeActive(); foregroundFinished = true }
      try await waitUntil("foreground must enter the real lifecycle callback") {
        await env.stateMachine.appDidBecomeActiveCallCount == 1
      }
      try await waitUntil("foreground must finish or expose its incorrect retirement/admission") {
        foregroundFinished || env.manager.connectionRequestID != nextRequest || env.manager.connectionState == .disconnected
      }
      #expect(env.manager.connectionRequestID == nextRequest, "foreground must preserve admitted B")
      #expect(env.manager.connectionState != .disconnected, "foreground must not retire B's actual handshake")
      await env.ble.releaseAllGates()
      _ = await exchange?.result
      let result = try await #require(replacement).value
      await foreground?.value
      #expect(env.manager.isCurrent(result), "B's actual connection request must finish current")
      #expect(env.manager.connectedDevice?.id == nextID)
      #expect(env.manager.lastConnectedDeviceID == nextID)
      #expect(try await env.manager.persistenceStore.fetchActiveDevice()?.id == nextID)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      await env.ble.releaseAllGates()
      replacement?.cancel()
      _ = await exchange?.result
      _ = await replacement?.result
      await foreground?.value
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `same request BLE recovery returns owned handoff during clock tail`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    await env.ble.holdReplies(for: .getTime)
    let connect = Task { try await env.connect(.bluetooth) }
    do {
      try await waitUntil("published Link must be in its actual clock query") { await env.ble.timeReads == 1 }
      let old = try #require(env.manager.currentConnection)
      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      try await waitUntil("owned BLE loss must retire the published Link") { env.manager.currentConnection == nil }
      await env.ble.releaseReplies(for: .getTime)
      let result = try await connect.value
      if case let .handoff(requestID) = result {
        #expect(requestID == old.requestID)
      } else {
        Issue.record("same request Link loss must return an owned handoff")
      }
      #expect(env.manager.isCurrent(result))
      await env.ble.simulateReconnected()
      try await waitUntil("same request OS rebuild must finish") {
        env.manager.connectionState == .ready && env.manager.currentConnection?.generation != old.generation
      }
      #expect(env.manager.connectionRequestID == old.requestID)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      await env.ble.releaseAllGates()
      connect.cancel()
      _ = await connect.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `old setup result uses already installed same request successor`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let resultGate = BLEReconnectionTestHarness.Gate()
    var heldGeneration: UUID?
    env.manager.testPostSetupResultHandler = { generation in
      guard heldGeneration == nil else { return }
      heldGeneration = generation
      await resultGate.wait()
    }
    await env.ble.holdReplies(for: .getTime)
    let connect = Task { try await env.connect(.bluetooth) }
    do {
      try await waitUntil("old setup must reach its actual clock query") { await env.ble.timeReads == 1 }
      let old = try #require(env.manager.currentConnection)
      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      try await waitUntil("physical loss must retire the old Link") { env.manager.currentConnection == nil }
      await env.ble.releaseReplies(for: .getTime)
      try await waitUntil("the old request result must remain held after setup finishes") { heldGeneration == old.generation }
      try await waitUntil("old Link retirement must join actual setup independently of its caller") {
        env.manager.connectionTeardownCount == 0
      }
      await env.ble.simulateReconnected()
      try await waitUntil("same-request successor must install before old result resumes") {
        env.manager.connectionState == .ready && env.manager.currentConnection?.generation != old.generation
      }
      let successor = try #require(env.manager.currentConnection)
      resultGate.open()
      let result = try await connect.value
      if case let .operational(snapshot) = result {
        #expect(snapshot.generation == successor.generation)
        #expect(snapshot.requestID == old.requestID)
      } else { Issue.record("an owned request must return its already installed successor") }
      #expect(env.manager.isCurrent(result))
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      resultGate.open()
      await env.ble.releaseAllGates()
      connect.cancel()
      _ = await connect.result
      await env.close()
      throw error
    }
    env.manager.testPostSetupResultHandler = nil
    await env.close()
  }

  @Test
  func `retirement joins pending listener repair without restarting old polling`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let repairGate = BLEReconnectionTestHarness.Gate()
    var repairStarted = false
    var foreground: Task<Void, Never>?
    var replacement: Task<ConnectionResult, Error>?
    do {
      _ = try await env.connect(.bluetooth)
      let old = try #require(env.manager.currentConnection)
      await old.services.messagePollingService.stopAutoFetch()
      let oldWireHistory = await env.ble.sentData
      env.manager.testMessageListenerRepairHandler = { generation in
        guard generation == old.generation else { return }
        repairStarted = true
        await repairGate.wait()
      }
      await env.stateMachine.setStubbedIsConnected(true)
      await env.stateMachine.setStubbedConnectedDeviceID(env.deviceID)
      foreground = Task { await env.manager.appDidBecomeActive() }
      try await waitUntil("foreground must enter actual missing-fetch repair") { repairStarted }
      replacement = Task { try await env.connect(.wifi) }
      try await waitUntil("replacement must retire the source Link") { env.manager.connectionRequestID != old.requestID }
      #expect(await env.wifi.connectCount == 0, "physical successor must wait for admitted old repair")
      repairGate.open()
      await foreground?.value
      let result = try await #require(replacement).value
      #expect(env.manager.isCurrent(result))
      #expect(await !old.services.messagePollingService.isAutoFetching)
      #expect(await env.ble.sentData == oldWireHistory, "retired repair must submit no old polling commands")
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      repairGate.open()
      await env.ble.releaseAllGates()
      await env.wifi.releaseAllGates()
      replacement?.cancel()
      await foreground?.value
      _ = await replacement?.result
      await env.close()
      throw error
    }
    env.manager.testMessageListenerRepairHandler = nil
    await env.close()
  }

  @Test(.timeLimit(.minutes(1)), arguments: [false, true])
  func `initial contacts timeout resync restores later message receipt`(foreground: Bool) async throws {
    let sessionClock = TestClock()
    let resyncClock = TestClock()
    let env = try ConnectionManagerRadioTestHarness(clock: resyncClock, sessionClock: sessionClock)
    await env.ble.setChannel(index: 0, name: "Dispatch", secret: Data(repeating: 1, count: 16))
    await env.ble.enqueueResponsePackets(for: .getContacts, packets: [])
    let connect = Task { try await env.connect(.bluetooth) }
    do {
      try await waitUntil("initial contact request must run after monitoring starts") {
        await env.ble.sentData.contains { $0.first == CommandCode.getContacts.rawValue }
      }
      let initial = try #require(env.manager.currentConnection)
      let initialLink = try #require(env.manager.installedLink)
      #expect(initial.services.isEventMonitoringActive)
      #expect(await !initial.services.messagePollingService.isAutoFetching)
      try await waitUntilClockAdvances(sessionClock, "real contacts timeout must schedule resync") {
        env.manager.resyncTask != nil
      }
      _ = try await connect.value
      try await waitUntilClockAdvances(resyncClock, "actual full resync must become usable") {
        env.manager.connectionState == .ready
      }
      if foreground {
        await env.stateMachine.setStubbedIsConnected(true)
        await env.stateMachine.setStubbedConnectedDeviceID(env.deviceID)
        await env.manager.appDidBecomeActive()
      }
      let readsBefore = await env.ble.sentData.filter { $0.first == CommandCode.getMessage.rawValue }.count
      var packet = Data([ResponseCode.channelMessageReceived.rawValue, 0, 0, 0])
      packet.append(withUnsafeBytes(of: UInt32(1_792_310_400).littleEndian) { Data($0) })
      packet.append(Data("Dispatch: after resync".utf8))
      await env.ble.enqueueResponsePackets(for: .getMessage, packets: [packet])
      await env.ble.inject(Data([ResponseCode.messagesWaiting.rawValue]))
      try await waitUntil("later messagesWaiting must issue getMessage after resync") {
        await env.ble.sentData.filter { $0.first == CommandCode.getMessage.rawValue }.count > readsBefore
      }
      try await waitUntil("fetched post-resync message must be persisted") {
        await (try? env.manager.persistenceStore.fetchMessages(radioID: initial.device.radioID, channelIndex: 0))?
          .contains { $0.text.contains("after resync") } == true
      }
      #expect(env.manager.currentConnection?.generation == initial.generation)
      #expect(await initial.services.messagePollingService.isAutoFetching)
      env.manager.sessionClockForTesting = nil
      _ = try await env.connect(.wifi)
      await env.manager.completeSync(
        initialLink, syncResult: FullSyncResult(contacts: .clean, channels: .clean, messages: .clean)
      )
      #expect(await !initial.services.messagePollingService.isAutoFetching)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      await env.ble.releaseAllGates()
      connect.cancel()
      _ = await connect.result
      await env.close()
      throw error
    }
    await env.close()
  }
}
