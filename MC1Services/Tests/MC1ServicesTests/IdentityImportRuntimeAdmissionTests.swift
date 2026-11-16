import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Identity import runtime admission")
@MainActor
struct IdentityImportRuntimeAdmissionTests {
  @Test(arguments: [false, true], [false, true])
  func `foreground cannot restart the source during identity observation`(
    hasGhost: Bool, holdBeforeCommit: Bool
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      harness.manager.testNotificationSystemOperations = .init(
        setBadgeCount: { _ in }, deliveredRequests: { [] }, removeDeliveredNotifications: { _ in }
      )
      if hasGhost { try await CanonicalIdentityFixture.prepareGhost(in: harness) }
      try await harness.connectBluetooth()
      await setPhysicallyConnected(harness)
      let source = try #require(harness.manager.currentConnection)
      let commitGate = IdentityRecoveryFixture.Gate()
      defer {
        commitGate.open()
        harness.manager.testIdentityBeforeCommit = nil
      }
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      if holdBeforeCommit {
        harness.manager.testIdentityBeforeCommit = { await commitGate.wait() }
      } else {
        await harness.bluetooth.holdReplies(for: .appStart)
      }
      let importing = harness.track(Task {
        try await source.services.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("identity observation should reach the held production boundary") {
        if holdBeforeCommit { return commitGate.arrived }
        return await harness.bluetooth.heldReplyCount(for: .appStart) == 1
      }
      let physicalStateConnected = await harness.stateMachine.isConnected
      #expect(physicalStateConnected)
      let physicalDeviceMatches = await harness.stateMachine.connectedDeviceID == harness.deviceA.id
      #expect(physicalDeviceMatches)
      let notAutoReconnecting = await harness.stateMachine.isAutoReconnecting == false
      #expect(notAutoReconnecting)
      let physicalTransportConnected = await harness.bluetooth.isConnected
      #expect(physicalTransportConnected)
      let sourceStillPublished = harness.manager.currentConnection?.generation == source.generation
      #expect(sourceStillPublished)
      let monitoringStopped = !source.services.isEventMonitoringActive
      #expect(monitoringStopped)
      let autoFetchStopped = await source.services.messagePollingService.isAutoFetching == false
      #expect(autoFetchStopped)
      let connectionsBeforeForeground = await harness.bluetooth.connectCount
      let disconnectsBeforeForeground = await harness.bluetooth.disconnectCount

      await harness.manager.appDidEnterBackground()
      await harness.manager.appDidBecomeActive()

      let restarted = source.services.isEventMonitoringActive
      #expect(!restarted)
      let autoFetchStopped2 = await source.services.messagePollingService.isAutoFetching == false
      #expect(autoFetchStopped2)
      let noForegroundConnect = await harness.bluetooth.connectCount == connectionsBeforeForeground
      #expect(noForegroundConnect)
      let noForegroundDisconnect = await harness.bluetooth.disconnectCount == disconnectsBeforeForeground
      #expect(noForegroundDisconnect)
      let sourceStillPublished2 = harness.manager.currentConnection?.generation == source.generation
      #expect(sourceStillPublished2)
      let advertKey = Data(repeating: 0xD7, count: ProtocolLimits.publicKeySize)
      let events = await source.services.session.events()
      let observed = harness.track(Task {
        for await event in events {
          if case let .newContact(contact) = event, contact.publicKey == advertKey { return }
        }
      })
      await harness.bluetooth.inject(newAdvertisement(key: advertKey))
      await observed.value
      if restarted {
        try await waitUntil("a restarted source must demonstrate its actual obsolete-partition effect") {
          await (try? harness.manager.persistenceStore.fetchDiscoveredNodes(radioID: harness.deviceA.radioID)
            .contains { $0.publicKey == advertKey }) == true
        }
      }
      let sourceDiscovery = try await harness.manager.persistenceStore.fetchDiscoveredNodes(radioID: harness.deviceA.radioID)
        .contains { $0.publicKey == advertKey }
      #expect(!sourceDiscovery)

      commitGate.open()
      await harness.bluetooth.releaseReplies(for: .appStart)
      try await importing.value
      let canonicalRadioID = hasGhost ? harness.deviceB.radioID : harness.deviceA.radioID
      let canonicalPartitionPublished = harness.manager.connectedDevice?.radioID == canonicalRadioID
      #expect(canonicalPartitionPublished)
      let runtimeReady = harness.manager.connectionState == .ready
      #expect(runtimeReady)
      let laterAdvertKey = Data(repeating: 0xD8, count: ProtocolLimits.publicKeySize)
      await harness.bluetooth.inject(newAdvertisement(key: laterAdvertKey))
      try await waitUntil("the replacement runtime should process later discovery") {
        await (try? harness.manager.persistenceStore.fetchDiscoveredNodes(radioID: canonicalRadioID)
          .contains { $0.publicKey == laterAdvertKey }) == true
      }
      if hasGhost {
        let laterSourceDiscovery = try await harness.manager.persistenceStore.fetchDiscoveredNodes(radioID: harness.deviceA.radioID)
          .contains { $0.publicKey == laterAdvertKey }
        #expect(!laterSourceDiscovery)
      }
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(laterIOWorks)
    }
  }

  @Test
  func `foreground still repairs an ordinary stopped runtime on its connected transport`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      await setPhysicallyConnected(harness)
      let source = try #require(harness.manager.currentConnection)
      await source.services.stopEventMonitoring()
      let monitoringStopped2 = !source.services.isEventMonitoringActive
      #expect(monitoringStopped2)
      let autoFetchStopped3 = await source.services.messagePollingService.isAutoFetching == false
      #expect(autoFetchStopped3)

      await harness.manager.appDidBecomeActive()

      let sourceStillPublished3 = harness.manager.currentConnection?.generation == source.generation
      #expect(sourceStillPublished3)
      let monitoringActive = source.services.isEventMonitoringActive
      #expect(monitoringActive)
      let autoFetchActive = await source.services.messagePollingService.isAutoFetching
      #expect(autoFetchActive)
      let repairedIOWorks = try await source.services.settingsService.getTime().timeIntervalSince1970 > 0
      #expect(repairedIOWorks)
    }
  }

  private func setPhysicallyConnected(_ harness: ConnectionRadioHandoffHarness) async {
    await harness.stateMachine.setStubbedIsConnected(true)
    await harness.stateMachine.setStubbedConnectedDeviceID(harness.deviceA.id)
    await harness.stateMachine.setStubbedIsAutoReconnecting(false)
  }

  @Test
  func `identity waits for an already-entered listener repair before its key write`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      await setPhysicallyConnected(harness)
      let sourceSnapshot = harness.manager.currentConnection
      let source = try #require(sourceSnapshot)
      await source.services.stopEventMonitoring()
      let repairGate = IdentityRecoveryFixture.Gate()
      defer {
        repairGate.open()
        harness.manager.testMessageListenerRepairHandler = nil
      }
      harness.manager.testMessageListenerRepairHandler = { _ in await repairGate.wait() }
      let repairing = harness.track(Task { await harness.manager.appDidBecomeActive() })
      try await waitUntil("ordinary repair should reach its direct auto-fetch admission") { repairGate.arrived }
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      let importing = harness.track(Task {
        try await source.services.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("identity preparation should close the source while repair remains entered") {
        !source.services.isAcceptingRuntimeWork
      }
      let beforeRelease = await harness.bluetooth.sentData
      let keyNotSubmitted = !beforeRelease.contains { $0.first == CommandCode.importPrivateKey.rawValue }
      #expect(keyNotSubmitted)
      repairGate.open()
      await repairing.value
      try await importing.value
      let sourceMonitoringStopped = !source.services.isEventMonitoringActive
      let sourceFetchingStopped = await !source.services.messagePollingService.isAutoFetching
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(sourceMonitoringStopped)
      #expect(sourceFetchingStopped)
      #expect(laterIOWorks)
    }
  }

  @Test
  func `identity joins monitoring startup already entered by connected foreground repair`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      await setPhysicallyConnected(harness)
      let sourceSnapshot = harness.manager.currentConnection
      let source = try #require(sourceSnapshot)
      await source.services.stopEventMonitoring()
      let startup = IdentityRecoveryFixture.Gate()
      defer {
        startup.open()
        source.services.testEventMonitoringStartHandler = nil
      }
      source.services.testEventMonitoringStartHandler = { await startup.wait() }
      let repairing = harness.track(Task { await harness.manager.appDidBecomeActive() })
      try await waitUntil("foreground repair should enter monitoring startup") { startup.arrived }
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      let importing = harness.track(Task {
        try await source.services.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("identity preparation should close the entered startup") {
        !source.services.isAcceptingRuntimeWork
      }
      let frames = await harness.bluetooth.sentData
      let keyNotSubmitted = !frames.contains { $0.first == CommandCode.importPrivateKey.rawValue }
      #expect(keyNotSubmitted)
      startup.open()
      await repairing.value
      try await importing.value
      await source.services.startEventMonitoring(radioID: harness.deviceA.radioID)
      let sourceMonitoringStopped = !source.services.isEventMonitoringActive
      let sourceFetchingStopped = await !source.services.messagePollingService.isAutoFetching
      let sourceAckWorkerStopped = await !source.services.messageService.isAckExpiryCheckingActive
      let ready = harness.manager.connectionState == .ready
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(sourceMonitoringStopped)
      #expect(sourceFetchingStopped)
      #expect(sourceAckWorkerStopped)
      #expect(ready)
      #expect(laterIOWorks)
    }
  }

  @Test(arguments: [false, true])
  func `identity-only entry points still activate ordinary setup`(standalone: Bool) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let sourceServices = harness.manager.services
      let source = try #require(sourceServices)
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      if standalone {
        _ = try await harness.manager.importIdentity(
          CanonicalIdentityFixture.privateKey, expectedServices: source, deviceID: harness.deviceA.id
        )
      } else {
        try await source.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      }
      let ready = harness.manager.connectionState == .ready
      let canonical = harness.manager.connectedDevice?.publicKey == CanonicalIdentityFixture.publicKey
      let frames = await harness.bluetooth.sentData
      let keyIndex = frames.firstIndex { $0.first == CommandCode.importPrivateKey.rawValue }
      let index = try #require(keyIndex)
      let synced = frames.suffix(from: index).contains { $0.first == CommandCode.getChannel.rawValue }
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(ready)
      #expect(canonical)
      #expect(synced)
      #expect(laterIOWorks)
    }
  }

  private func newAdvertisement(key: Data) -> Data {
    let name = Data("Runtime discovery".utf8)
    var packet = Data([ResponseCode.newAdvertisement.rawValue]) + key
    packet.append(contentsOf: [ContactType.chat.rawValue, 0, 0xFF])
    packet.append(Data(repeating: 0, count: 64))
    packet.append(name)
    packet.append(Data(repeating: 0, count: 32 - name.count))
    packet.append(Data(repeating: 0, count: 16))
    return packet
  }
}
