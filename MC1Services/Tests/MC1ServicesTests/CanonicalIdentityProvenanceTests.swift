import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical identity provenance")
@MainActor
struct CanonicalIdentityProvenanceTests {
  @Test
  func `accepted key without SelfInfo cannot merge a later foreign identity`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let old = try #require(harness.manager.services)
      let stateA = old.settingsService.radioWiFiState
      _ = try await old.settingsService.runCliCommand("set wifi.enabled 0")
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      await harness.bluetooth.holdReplies(for: .importPrivateKey)
      let importing = harness.track(Task {
        try await old.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("private key should reach its held acknowledgement") {
        await harness.bluetooth.sentData.contains { $0.first == CommandCode.importPrivateKey.rawValue }
      }
      await harness.bluetooth.setSendError(MeshTransportError.notConnected)
      await harness.bluetooth.releaseReplies(for: .importPrivateKey)
      await #expect(throws: (any Error).self) { try await importing.value }
      let unresolved = try #require(harness.manager.pendingIdentityReconciliations[harness.deviceA.id])
      #expect(unresolved.publicKey == nil)
      #expect(stateA.rebootPending)
      #expect(stateA.stableRevision() == nil)

      let foreignKey = Data(repeating: 0xC3, count: ProtocolLimits.publicKeySize)
      await harness.bluetooth.setSendError(nil)
      await harness.bluetooth.setPublicKey(foreignKey, for: harness.deviceA.id)
      await #expect(throws: ConnectionRequestFailure.self) {
        _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      }
      #expect(harness.manager.services == nil)
      #expect(harness.manager.connectedDevice == nil)
      let unchangedA = try #require(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id))
      #expect(unchangedA.publicKey == harness.deviceA.publicKey)
      #expect(unchangedA.radioID == harness.deviceA.radioID)
      #expect(try await harness.manager.persistenceStore.fetchDevice(radioID: harness.deviceB.radioID)?.radioID == harness.deviceB.radioID)

      await harness.wifi.setDeviceID(harness.deviceB.id)
      await harness.wifi.setPublicKey(foreignKey, for: harness.deviceB.id)
      _ = try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242)
      let foreign = try #require(harness.manager.connectedDevice)
      #expect(foreign.publicKey == foreignKey)
      #expect(foreign.radioID != harness.deviceA.radioID)
      #expect(foreign.radioID != harness.deviceB.radioID)
      #expect(harness.manager.services?.settingsService.radioWiFiState.rebootPending == false)
      #expect(stateA.rebootPending)
      #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id]?.publicKey == nil)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @Test
  func `explicit rejected key does not create a pending identity merge`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let old = try #require(harness.manager.services)
      await harness.bluetooth.rejectNext(.importPrivateKey)
      await #expect(throws: (any Error).self) {
        try await old.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      }
      #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id] == nil)
      _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      #expect(harness.manager.connectedDevice?.publicKey == harness.deviceA.publicKey)
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceA.radioID)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @Test
  func `old public config reads and writes close before the private key acknowledgement`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.installedLink)
      let old = source.services.nodeConfigService
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      await harness.bluetooth.holdReplies(for: .importPrivateKey)
      let importing = harness.track(Task {
        try await old.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("private key should reach its held acknowledgement") {
        await harness.bluetooth.sentData.contains { $0.first == CommandCode.importPrivateKey.rawValue }
      }
      let before = await harness.bluetooth.sentData.count
      let config = MeshCoreNodeConfig(channels: [.init(name: "Stale", secret: String(repeating: "AB", count: 16))])
      await #expect(throws: CancellationError.self) { _ = try await old.exportConfig(sections: ConfigSections(channels: true)) }
      await #expect(throws: CancellationError.self) { _ = try await old.previewImport(config, sections: ConfigSections(channels: true)) }
      await #expect(throws: CancellationError.self) {
        try await old.importConfig(config, sections: ConfigSections(channels: true), radioID: harness.deviceA.radioID)
      }
      await #expect(throws: CancellationError.self) { try await source.resource.session.reboot() }
      #expect(await harness.bluetooth.sentData.count == before)
      await harness.bluetooth.releaseReplies(for: .importPrivateKey)
      try await importing.value
      let fresh = try #require(harness.manager.services?.nodeConfigService)
      await #expect(throws: CancellationError.self) { _ = try await old.exportConfig(sections: ConfigSections(channels: true)) }
      try await fresh.importConfig(config, sections: ConfigSections(channels: true), radioID: harness.deviceB.radioID)
      #expect(try await harness.manager.persistenceStore.fetchChannels(radioID: harness.deviceB.radioID).contains { $0.name == "Stale" })
      #expect(try await harness.manager.persistenceStore.fetchChannels(radioID: harness.deviceA.radioID).contains { $0.name == "Stale" } == false)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @Test
  func `a public export already reading the old session cannot issue its next command after handoff`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.installedLink)
      var capabilities = Data([ResponseCode.deviceInfo.rawValue, 14, 1, 2])
      capabilities.append(Data(repeating: 0, count: 76))
      await harness.bluetooth.setResponsePackets(for: .deviceQuery, packets: [capabilities])
      await harness.bluetooth.holdReplies(for: .getChannel)
      let readsBefore = await harness.bluetooth.sentData.filter { $0.first == CommandCode.getChannel.rawValue }.count
      let exporting = harness.track(Task {
        try await source.services.nodeConfigService.exportConfig(sections: ConfigSections(channels: true))
      })
      try await waitUntil("the old export should hold its first real channel reply") {
        await harness.bluetooth.sentData.filter { $0.first == CommandCode.getChannel.rawValue }.count > readsBefore
      }
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      let importing = harness.track(Task {
        try await harness.manager.importIdentity(
          CanonicalIdentityFixture.privateKey,
          expectedServices: source.services, deviceID: harness.deviceA.id
        )
      })
      try await waitUntil("handoff should close old service admission before the held read settles") {
        await source.services.settingsService.radioWiFiOperationsRetired
      }
      await harness.bluetooth.releaseReplies(for: .getChannel)
      await #expect(throws: CancellationError.self) { _ = try await exporting.value }
      _ = try await importing.value
      let frames = await harness.bluetooth.sentData
      let keyIndex = try #require(frames.firstIndex { $0.first == CommandCode.importPrivateKey.rawValue })
      #expect(frames[..<keyIndex].filter { $0.first == CommandCode.getChannel.rawValue }.count == readsBefore + 1)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }
}
