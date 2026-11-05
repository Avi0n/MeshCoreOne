import Foundation
@testable import MC1Services
import Testing

@Suite("Platform Bluetooth association removal")
@MainActor
struct ConnectionManagerPlatformRemovalTests {
  @Test(arguments: [false, true], [false, true])
  func `failed pairing removes Bluetooth while preserving radio data`(hasSystemRegistry: Bool, hasWiFi: Bool) async throws {
    let env = try PlatformRemovalTestHarness(hasSystemRegistry: hasSystemRegistry)
    do {
      let saved = try await env.saveDevice(hasWiFi: hasWiFi)
      let history = MessageDTO(from: Message(radioID: saved.radioID, channelIndex: 0, text: "Saved history"))
      let pending = PendingSendDTO(
        id: UUID(), radioID: saved.radioID, messageID: history.id, kind: .channel,
        contactID: nil, channelIndex: 0, isResend: false, messageText: history.text,
        messageTimestamp: history.timestamp, localNodeName: nil, sequence: 1, enqueuedAt: Date()
      )
      try await env.manager.persistenceStore.saveMessage(history)
      try await env.manager.persistenceStore.upsertPendingSend(pending)
      let failedRequestID = try await env.failConnection()
      await env.seedBondVerification()
      let receipt = try await env.requireRemoved(env.manager.removeFailedPairing(deviceID: saved.id))

      #expect(receipt.requestID != failedRequestID)
      #expect(env.manager.currentConnection == nil)
      #expect(env.manager.connectionState == .disconnected)
      #expect(!env.manager.connectionIntent.wantsConnection)
      let stored = try #require(await env.manager.persistenceStore.fetchDevice(radioID: saved.radioID))
      #expect(stored.publicKey == saved.publicKey)
      #expect(stored.radioID == saved.radioID)
      #expect(!stored.isActive)
      if hasWiFi {
        #expect(stored.id == saved.id)
        #expect(stored.connectionMethods == [PlatformRemovalTestHarness.wifi])
        #expect(stored.lastUsedConnectionMethod == PlatformRemovalTestHarness.wifi)
        #expect(env.manager.lastConnectedDeviceID == saved.id)
        #expect(env.manager.lastConnectedRadioID == saved.radioID)
        #expect(env.manager.lastConnectedDeviceName == saved.nodeName)
      } else {
        #expect(stored.id != saved.id)
        #expect(stored.connectionMethods.isEmpty)
        #expect(try await env.manager.persistenceStore.fetchDevice(id: saved.id) == nil)
        #expect(env.manager.lastConnectedDeviceID == nil)
        #expect(env.manager.lastConnectedRadioID == nil)
        #expect(env.manager.lastConnectedDeviceName == nil)
      }
      #expect(try await env.manager.persistenceStore.fetchMessage(id: history.id)?.radioID == saved.radioID)
      #expect(try await env.manager.persistenceStore.fetchPendingSends(radioID: saved.radioID) == [pending])
      #expect(await env.stateMachine.recordedBondVerifications[saved.id] == nil)
      #expect(LastConnectionStore(defaults: env.defaults).bondVerificationDate(for: saved.id) == nil)
      #expect(await env.radio.connectCount == 1)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `association readded before cleanup remains saved`(hasWiFi: Bool) async throws {
    let env = try PlatformRemovalTestHarness(hasSystemRegistry: true)
    let gate = PlatformRemovalTestHarness.Gate()
    var removing: Task<ConnectionRemovalResult, Error>?
    do {
      let saved = try await env.saveDevice(hasWiFi: hasWiFi)
      env.registerAccessory()
      let failedRequestID = try await env.failConnection()
      let predecessor = env.manager.enqueueMetadata { _ in await gate.wait() }
      try await waitUntil("metadata predecessor must be held") { gate.entered }
      let removal = Task { try await env.manager.removeFailedPairing(deviceID: saved.id) }
      removing = removal
      try await waitUntil("system removal must finish before local metadata cleanup") {
        env.ask.removeAccessoryCallCount == 1 && env.manager.desiredRequest?.target == .disconnected
      }
      #expect(env.ask.accessory(for: saved.id) == nil)
      env.registerAccessory()
      gate.open()
      _ = try await predecessor.value
      let receipt = try await env.requireRemoved(removal.value)
      #expect(receipt.requestID != failedRequestID)
      let retained = try #require(await env.manager.persistenceStore.fetchDevice(id: saved.id))
      #expect(retained.radioID == saved.radioID)
      #expect(retained.publicKey == saved.publicKey)
      #expect(retained.connectionMethods == saved.connectionMethods)
      #expect(env.ask.accessory(for: saved.id) != nil)
      #expect(env.manager.currentConnection == nil)
      #expect(!env.manager.connectionIntent.wantsConnection)
    } catch {
      gate.open()
      _ = await removing?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `stale no registry removal preserves a newer same handle connection`() async throws {
    let env = try PlatformRemovalTestHarness(hasSystemRegistry: false)
    do {
      await env.manager.wireTransportHandlers()
      _ = try await env.manager.connect(to: env.deviceID, forceFullSync: true, forceReconnect: true)
      let context = try #require(env.manager.captureCurrentRemoval())
      let oldRequestID = env.manager.connectionRequestID
      let result = try await env.manager.connect(to: env.deviceID, forceFullSync: true, forceReconnect: true)
      let successor = try #require(env.manager.currentConnection)
      let link = try #require(env.manager.installedLink)
      let before = try #require(await env.manager.persistenceStore.fetchDevice(id: env.deviceID))
      #expect(successor.requestID != oldRequestID)
      let removal = try await env.manager.startRemoval(
        context, policy: .bluetoothAssociation(pairingFailed: false),
        reason: .forgetDevice, sourceIsCurrent: false
      ).value
      guard case .superseded = removal else {
        Issue.record("A stale removal must leave the successor in control")
        throw ConnectionError.notConnected
      }
      #expect(env.manager.isCurrent(result))
      #expect(env.manager.installedLink === link)
      #expect(link.resource.binding.isActive)
      #expect(env.manager.currentConnection?.generation == successor.generation)
      #expect(env.manager.connectionIntent.wantsConnection)
      #expect(env.manager.lastConnectedDeviceID == successor.device.id)
      #expect(env.manager.lastConnectedRadioID == successor.device.radioID)
      let retained = try #require(await env.manager.persistenceStore.fetchDevice(id: env.deviceID))
      #expect(retained.radioID == before.radioID)
      #expect(retained.publicKey == before.publicKey)
      #expect(retained.connectionMethods == before.connectionMethods)
      #expect(retained.isActive)
      #expect(await env.stateMachine.recordedBondVerifications[env.deviceID] != nil)
      #expect(LastConnectionStore(defaults: env.defaults).bondVerificationDate(for: env.deviceID) != nil)
      #expect(await env.radio.connectCount == 2)
      #expect(try await successor.services.session.getTime().timeIntervalSince1970 > 0)
    } catch { await env.close(); throw error }
    await env.close()
  }
}
