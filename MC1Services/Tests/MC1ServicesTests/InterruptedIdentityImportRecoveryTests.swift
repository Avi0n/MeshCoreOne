import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import os
import Testing

@Suite("Interrupted identity import recovery")
@MainActor
struct InterruptedIdentityImportRecoveryTests {
  struct RecoverySample: Sendable {
    var route: RecoveryRoute
    var hasGhost: Bool
  }

  /// One case for each lost reply, the canonical ghost partition, and a WiFi source.
  nonisolated static let recoverySamples: [RecoverySample] = [
    RecoverySample(route: .bluetoothAfterLostSelfInfo, hasGhost: false),
    RecoverySample(route: .bluetoothAfterLostAcknowledgement, hasGhost: false),
    RecoverySample(route: .bluetoothAfterLostAcknowledgement, hasGhost: true),
    RecoverySample(route: .wifiAfterLostSelfInfo, hasGhost: false),
  ]

  @Test(arguments: recoverySamples)
  func `ordinary source reconnect stops until explicit identity recovery after lost replies`(
    _ sample: RecoverySample
  ) async throws {
    let route = sample.route
    let hasGhost = sample.hasGhost
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      if hasGhost { try await CanonicalIdentityFixture.prepareGhost(in: harness) }
      let secret = Data(repeating: 0xCD, count: 16)
      await harness.bluetooth.setChannel(index: 0, name: "Retained", secret: secret)
      await harness.wifi.setChannel(index: 0, name: "Retained", secret: secret)
      let activeRadio = route.usesWiFi ? harness.wifi : harness.bluetooth
      let clock = TestClock()
      if route.usesWiFi { await harness.wifi.setDeviceID(harness.deviceA.id) }
      try await openSource(harness, overWiFi: route.usesWiFi, clock: clock, radio: activeRadio)
      let source = try #require(harness.manager.services)
      let store = harness.manager.persistenceStore
      let stateA = source.settingsService.radioWiFiState
      let stateB = harness.manager.radioWiFiState(for: harness.deviceB.radioID)
      _ = try await source.settingsService.runCliCommand("set wifi.enabled 0")
      let oldPending = try await source.messageService.createPendingChannelMessage(
        text: "Original partition unsent", channelIndex: 0, radioID: harness.deviceA.radioID
      )
      _ = try await store.insertPendingSendAssigningSequence(
        PendingSendDTO(envelope: envelope(oldPending), radioID: harness.deviceA.radioID)
      )
      var canonicalPending: MessageDTO?
      if hasGhost {
        try await store.saveChannel(ChannelDTO.testChannel(
          radioID: harness.deviceB.radioID, name: "Retained", secret: secret
        ))
        let pending = try await source.messageService.createPendingChannelMessage(
          text: "Canonical partition waiting", channelIndex: 0, radioID: harness.deviceB.radioID
        )
        canonicalPending = pending
        _ = try await store.insertPendingSendAssigningSequence(
          PendingSendDTO(envelope: envelope(pending), radioID: harness.deviceB.radioID)
        )
      }
      let startsBefore = await activeRadio.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count
      if route.losesAcknowledgement {
        await activeRadio.enqueueResponsePackets(for: .importPrivateKey, packets: [])
      } else {
        await activeRadio.enqueueResponsePackets(for: .appStart, packets: [])
      }
      let importFinished = OSAllocatedUnfairLock(initialState: false)
      let importing = harness.track(Task {
        defer { importFinished.withLock { $0 = true } }
        try await source.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("the actual private-key import should reach the firmware") {
        await activeRadio.sentData.contains { $0.first == CommandCode.importPrivateKey.rawValue }
      }
      await activeRadio.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      try await waitForLostImportReply(clock, radio: activeRadio, harness: harness, finished: importFinished)
      await #expect(throws: (any Error).self) { try await importing.value }
      let startsAfter = await activeRadio.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count
      #expect(startsAfter == startsBefore + (route.losesAcknowledgement ? 0 : 1))
      let servicesWereRetired = harness.manager.services == nil
      #expect(servicesWereRetired)
      #expect(stateA.rebootPending)
      #expect(stateA.stableRevision() == nil)
      #expect(try await store.fetchDevice(id: harness.deviceA.id)?.publicKey == harness.deviceA.publicKey)
      #expect(try await store.fetchDevice(id: harness.deviceA.id)?.radioID == harness.deviceA.radioID)

      let startsBeforeReconnect = await activeRadio.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count
      if route.usesWiFi {
        await #expect(throws: ConnectionRequestFailure.self) {
          _ = try await harness.manager.connectViaWiFi(host: "source.local", port: 4242)
        }
      } else {
        await #expect(throws: ConnectionRequestFailure.self) {
          _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
        }
      }
      let sourceWasNotPublished = harness.manager.connectedDevice == nil && harness.manager.services == nil
      #expect(sourceWasNotPublished)
      #expect(harness.manager.desiredRequest?.target == .disconnected)
      #expect(!harness.manager.connectionIntent.wantsConnection)
      #expect(stateA.rebootPending)
      #expect(!stateB.rebootPending)
      #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id]?.publicKey == nil)
      #expect(try await store.fetchDevice(id: harness.deviceA.id)?.radioID == harness.deviceA.radioID)
      #expect(try await store.fetchDevice(id: harness.deviceA.id)?.publicKey == harness.deviceA.publicKey)
      #expect(try await store.fetchMessage(id: oldPending.id)?.status == .pending)
      #expect(try await store.fetchPendingSends(radioID: harness.deviceA.radioID).contains { $0.messageID == oldPending.id })
      if let canonicalPending {
        #expect(try await store.fetchMessage(id: canonicalPending.id)?.status == .pending)
      }
      let frames = await activeRadio.sentData
      let startsAfterReconnect = frames.filter { $0.first == CommandCode.appStart.rawValue }.count
      let importsBeforeRetry = frames.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
      let originalQueueWasNotSent = !frames.contains { $0.range(of: Data(oldPending.text.utf8)) != nil }
      #expect(startsAfterReconnect == startsBeforeReconnect + 1)
      #expect(importsBeforeRetry == 1)
      #expect(originalQueueWasNotSent)
      if let canonicalPending {
        let canonicalQueueWasNotSent = !frames.contains { $0.range(of: Data(canonicalPending.text.utf8)) != nil }
        #expect(canonicalQueueWasNotSent)
      }

      let recovery = try #require(harness.manager.identityImportRecoveries.first)
      let result = try await harness.manager.retryIdentityImport(
        confirmation: recovery, privateKey: CanonicalIdentityFixture.privateKey
      )
      guard case .operational = result else { Issue.record("Identity recovery did not become operational"); return }
      let recovered = try #require(harness.manager.connectedDevice)
      let live = try #require(harness.manager.services)
      let expectedRadioID = hasGhost ? harness.deviceB.radioID : harness.deviceA.radioID
      #expect(recovered.id == harness.deviceA.id)
      #expect(recovered.radioID == expectedRadioID)
      #expect(recovered.publicKey == CanonicalIdentityFixture.publicKey)
      #expect(live.settingsService.radioWiFiState === (hasGhost ? stateB : stateA))
      #expect(live.settingsService.radioWiFiState.rebootPending)
      #expect(live.settingsService.radioWiFiState.stableRevision() != nil)
      #expect(harness.manager.identityImportRecoveries.isEmpty)
      let replayed = canonicalPending ?? oldPending
      try await waitUntil("explicit recovery should hydrate only its canonical partition") {
        let message = try? await store.fetchMessage(id: replayed.id)
        let rows = await (try? store.fetchPendingSends(radioID: expectedRadioID)) ?? []
        return message?.status == .sent && !rows.contains { $0.messageID == replayed.id }
      }
      if hasGhost {
        #expect(try await store.fetchPendingSends(radioID: harness.deviceA.radioID).contains { $0.messageID == oldPending.id })
        #expect(try await store.fetchMessage(id: oldPending.id)?.status == .pending)
      }
      let fresh = try await live.messageService.createPendingChannelMessage(
        text: "Explicit recovery queue is live", channelIndex: 0, radioID: expectedRadioID
      )
      try await live.chatSendQueueService.enqueueChannel(envelope(fresh))
      try await waitUntil("fresh recovered send should complete on the actual session") {
        await (try? store.fetchMessage(id: fresh.id)?.status) == .sent
      }
      let recoveredFrames = await activeRadio.sentData
      let importCount = recoveredFrames.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
      let sentCanonical = recoveredFrames.contains { $0.range(of: Data(replayed.text.utf8)) != nil }
      let sentFresh = recoveredFrames.contains { $0.range(of: Data(fresh.text.utf8)) != nil }
      #expect(importCount == 2)
      #expect(sentCanonical)
      #expect(sentFresh)
      if hasGhost {
        let oldQueueStayedPut = !recoveredFrames.contains { $0.range(of: Data(oldPending.text.utf8)) != nil }
        #expect(oldQueueStayedPut)
      }
      _ = try await live.settingsService.runCliCommand("set wifi.enabled 1")
      #expect(live.settingsService.radioWiFiState.rebootPending)
      #expect(try await live.settingsService.getTime().timeIntervalSince1970 > 0)
    }
  }

  @Test(arguments: [false, true])
  func `fresh WiFi endpoint after interrupted Bluetooth import is a separate ordinary choice`(hasGhost: Bool) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      if hasGhost { try await CanonicalIdentityFixture.prepareGhost(in: harness) }
      let clock = TestClock()
      try await openSource(harness, overWiFi: false, clock: clock, radio: harness.bluetooth)
      let source = try #require(harness.manager.services)
      _ = try await source.settingsService.runCliCommand("set wifi.enabled 0")
      await harness.bluetooth.enqueueResponsePackets(for: .importPrivateKey, packets: [])
      let importFinished = OSAllocatedUnfairLock(initialState: false)
      let importing = harness.track(Task {
        defer { importFinished.withLock { $0 = true } }
        try await source.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      })
      try await waitForLostImportReply(clock, radio: harness.bluetooth, harness: harness, finished: importFinished)
      await #expect(throws: (any Error).self) { try await importing.value }
      await harness.wifi.setDeviceID(harness.deviceB.id)
      await harness.wifi.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceB.id)
      _ = try await harness.manager.connectViaWiFi(host: "fresh.local", port: 4242)
      #expect(harness.manager.connectedDevice?.publicKey == CanonicalIdentityFixture.publicKey)
      #expect(harness.manager.connectedDevice?.radioID != harness.deviceA.radioID)
      #expect((harness.manager.connectedDevice?.radioID == harness.deviceB.radioID) == hasGhost)
      #expect(source.settingsService.radioWiFiState.rebootPending)
      #expect(harness.manager.services?.settingsService.radioWiFiState.rebootPending == false)
      #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id] != nil)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  private func openSource(
    _ harness: ConnectionRadioHandoffHarness,
    overWiFi: Bool,
    clock: TestClock,
    radio: ConnectionTestRadioTransport
  ) async throws {
    harness.manager.sessionClockForTesting = clock
    try await resultAdvancingMatchedSessionClock(clock, observation: {
      await sessionClockObservation(radio: radio, session: harness.manager.session)
    }) {
      if overWiFi {
        _ = try await harness.manager.connectViaWiFi(host: "source.local", port: 4242)
      } else {
        try await harness.connectBluetooth()
      }
    }
    harness.manager.sessionClockForTesting = nil
  }

  private func waitForLostImportReply(
    _ clock: TestClock,
    radio: ConnectionTestRadioTransport,
    harness: ConnectionRadioHandoffHarness,
    finished: OSAllocatedUnfairLock<Bool>
  ) async throws {
    try await advanceParkedMatchedSessionClock(clock, "lost identity reply should time out", observation: {
      await sessionClockObservation(radio: radio, session: harness.manager.session)
    }) {
      finished.withLock { $0 }
    }
  }

  private func envelope(_ message: MessageDTO) -> ChannelMessageEnvelope {
    ChannelMessageEnvelope(
      messageID: message.id, channelIndex: 0, isResend: false, messageText: message.text,
      messageTimestamp: message.timestamp, localNodeName: "Recovery radio"
    )
  }

  enum RecoveryRoute: Sendable {
    case bluetoothAfterLostSelfInfo
    case bluetoothAfterLostAcknowledgement
    case wifiAfterLostSelfInfo
    case wifiAfterLostAcknowledgement

    var usesWiFi: Bool {
      switch self {
      case .bluetoothAfterLostSelfInfo, .bluetoothAfterLostAcknowledgement: false
      case .wifiAfterLostSelfInfo, .wifiAfterLostAcknowledgement: true
      }
    }

    var losesAcknowledgement: Bool {
      switch self {
      case .bluetoothAfterLostSelfInfo, .wifiAfterLostSelfInfo: false
      case .bluetoothAfterLostAcknowledgement, .wifiAfterLostAcknowledgement: true
      }
    }
  }
}
