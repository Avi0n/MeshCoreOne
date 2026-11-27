import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Mesh request scheduler connection guarantees", .timeLimit(.minutes(1)))
@MainActor
struct MeshRequestSchedulerConnectionTests {
  @Test
  func `deferred route restore replays after syncContacts before ready and retains on failure`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let key = Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize)
      let savedPath = Data([0x11, 0x22])
      let contact = ContactDTO.testContact(
        radioID: harness.deviceA.radioID,
        publicKey: key,
        typeRawValue: ContactType.repeater.rawValue,
        outPathLength: PacketBuilder.floodPathSentinel,
        outPath: Data()
      )
      try await harness.manager.persistenceStore.saveContact(contact)
      await harness.bluetooth.setContact(contact.toContactFrame().toMeshContact())

      harness.manager.recordPendingRouteRestore(PendingRouteRestore(
        radioID: harness.deviceA.radioID,
        publicKey: key,
        outPath: savedPath,
        outPathLength: 2
      ))

      await harness.bluetooth.simulateAutoReconnecting(deviceID: harness.deviceA.id)
      try await waitUntil("disconnect clears runtime") {
        harness.manager.currentConnection == nil
      }
      await harness.bluetooth.simulateReconnected()
      try await waitUntil("ready after deferred restore") {
        harness.manager.connectionState == .ready
      }
      #expect(harness.manager.pendingRouteRestores[harness.deviceA.radioID] == nil)
      let readyLink = try #require(harness.manager.installedLink)
      let syncedContact = try #require(await readyLink.resource.session.getContact(publicKey: key))
      #expect(syncedContact.outPath == savedPath)
      #expect(syncedContact.outPathLength == 2)

      let failedKey = Data(repeating: 0xCD, count: ProtocolLimits.publicKeySize)
      let pending = PendingRouteRestore(
        radioID: harness.deviceA.radioID,
        publicKey: failedKey,
        outPath: Data([0x33]),
        outPathLength: 1
      )
      harness.manager.recordPendingRouteRestore(pending)
      await readyLink.resource.session.stop()
      await harness.manager.drainPendingRouteRestores(for: readyLink)
      #expect(harness.manager.pendingRouteRestores[harness.deviceA.radioID]?[failedKey] == pending)
    }
  }

  @Test
  func `ghost adoption remaps pending route restores and disconnects remote sessions on both radios`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      let repeaterOnA = try await harness.addMembership(radioID: harness.deviceA.radioID, connected: true)
      let repeaterOnB = RemoteNodeSessionDTO.testSession(
        radioID: harness.deviceB.radioID,
        publicKey: Data(repeating: 0x55, count: 32),
        role: .repeater,
        isConnected: true
      )
      try await harness.manager.persistenceStore.saveRemoteNodeSessionDTO(repeaterOnB)

      let restoreKey = Data(repeating: 0x77, count: ProtocolLimits.publicKeySize)
      let floodContact = ContactDTO.testContact(
        radioID: harness.deviceB.radioID,
        publicKey: restoreKey,
        typeRawValue: ContactType.repeater.rawValue,
        outPathLength: PacketBuilder.floodPathSentinel,
        outPath: Data()
      )
      await harness.bluetooth.setContact(floodContact.toContactFrame().toMeshContact())
      harness.manager.recordPendingRouteRestore(PendingRouteRestore(
        radioID: harness.deviceA.radioID,
        publicKey: restoreKey,
        outPath: Data([0x0A, 0x0B]),
        outPathLength: 2
      ))

      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)

      try await source.nodeConfigService.importConfig(
        MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
        sections: ConfigSections(nodeIdentity: true),
        radioID: harness.deviceA.radioID
      )

      try await waitUntil("reconciliation updates canonical radio") {
        harness.manager.connectionState == .ready
          && harness.manager.connectedDevice?.radioID == harness.deviceB.radioID
      }

      #expect(harness.manager.pendingRouteRestores[harness.deviceA.radioID] == nil)
      #expect(harness.manager.pendingRouteRestores[harness.deviceB.radioID] == nil)
      let readyLink = try #require(harness.manager.installedLink)
      let restoredOnB = try #require(await readyLink.resource.session.getContact(publicKey: restoreKey))
      #expect(restoredOnB.outPath == Data([0x0A, 0x0B]))
      #expect(restoredOnB.outPathLength == 2)
      #expect(try await !harness.isConnected(repeaterOnA))
      #expect(try await !harness.isConnected(repeaterOnB))
    }
  }

  @Test
  func `external key change while link is down disconnects all remote sessions for that radio`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      let repeater = RemoteNodeSessionDTO.testSession(
        radioID: harness.deviceA.radioID,
        publicKey: Data(repeating: 0x88, count: 32),
        role: .repeater,
        isConnected: true
      )
      try await harness.manager.persistenceStore.saveRemoteNodeSessionDTO(repeater)
      try #require(try await harness.isConnected(repeater))

      let rotatedKey = Data(repeating: 0xE9, count: ProtocolLimits.publicKeySize)
      await harness.bluetooth.setPublicKey(rotatedKey, for: harness.deviceA.id)

      try await harness.connectBluetooth()
      #expect(try await !harness.isConnected(repeater))
    }
  }

  @Test
  func `external key change across BLE recovery drops membershipBatch and leaves rooms disconnected`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      let room = try await harness.addMembership(radioID: harness.deviceA.radioID, connected: true)
      let repeater = RemoteNodeSessionDTO.testSession(
        radioID: harness.deviceA.radioID,
        publicKey: Data(repeating: 0x89, count: 32),
        role: .repeater,
        isConnected: true
      )
      try await harness.manager.persistenceStore.saveRemoteNodeSessionDTO(repeater)
      try await harness.connectBluetooth()
      try await waitUntil("BLE lifecycle handler is installed") {
        await harness.bluetooth.hasLifecycleHandler
      }

      await harness.bluetooth.simulateAutoReconnecting(deviceID: harness.deviceA.id)
      try await waitUntil("BLE recovery captures room membership batch") {
        harness.manager.currentConnection == nil && harness.manager.membershipBatch?.sessionIDs == [room.id]
      }

      let rotatedKey = Data(repeating: 0xEA, count: ProtocolLimits.publicKeySize)
      await harness.bluetooth.setPublicKey(rotatedKey, for: harness.deviceA.id)
      await harness.bluetooth.simulateReconnected()
      try await waitUntil("reconnect reaches ready after key rotation") {
        harness.manager.connectionState == .ready
      }

      #expect(harness.manager.membershipBatch == nil)
      #expect(try await !harness.isConnected(room))
      #expect(try await !harness.isConnected(repeater))
      let loginFrames = await harness.bluetooth.sentData.filter { $0.first == CommandCode.sendLogin.rawValue }
      #expect(loginFrames.isEmpty)
    }
  }
}
