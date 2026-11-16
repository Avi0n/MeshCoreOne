import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Identity import after a transport completion failure")
@MainActor
struct IdentityImportTransportFailureTests {
  @Test(arguments: Failure.allCases, [false, true])
  func `an admitted queue send failure cannot lend its terminal response to identity import`(
    failure: Failure, oldReplyWasOK: Bool
  ) async throws {
    try await exerciseFailure(
      failure, oldReplyWasOK: oldReplyWasOK, cancelCaller: false, settledBeforeAdmission: false
    )
  }

  @Test
  func `cancelling the old caller cannot hide its actual transport completion failure`() async throws {
    try await exerciseFailure(
      .bluetoothTimeout, oldReplyWasOK: true, cancelCaller: true, settledBeforeAdmission: false
    )
  }

  @Test
  func `a transport failure already settled before identity admission also refuses the key write`() async throws {
    try await exerciseFailure(
      .bluetoothTimeout, oldReplyWasOK: false, cancelCaller: false, settledBeforeAdmission: true
    )
  }

  @Test(arguments: PriorOutcome.allCases)
  func `a consumed rejection or local next-write cancellation does not taint identity admission`(
    priorOutcome: PriorOutcome
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.installedLink)
      let scopesBefore = await harness.bluetooth.sentData.filter { $0.first == CommandCode.setFloodScope.rawValue }.count
      await harness.bluetooth.holdReplies(for: .setFloodScope)
      if priorOutcome == .rejectedScope { await harness.bluetooth.rejectNext(.setFloodScope) }
      let ordinary = harness.track(Task {
        try await source.resource.session.sendChannelMessage(
          channel: 0, text: "No next message", timestamp: Date(), floodScope: .unscoped
        )
      })
      var importing: Task<Void, Error>?
      do {
        try await waitUntil("the ordinary scope exchange must have its own outstanding response") {
          let scopes = await harness.bluetooth.sentData.filter { $0.first == CommandCode.setFloodScope.rawValue }.count
          let scopeHeld = await harness.bluetooth.heldReplyCount(for: .setFloodScope) >= 1
          return scopes > scopesBefore && scopeHeld
        }
        if priorOutcome == .cancelledBeforeMessage {
          ordinary.cancel()
          // The submitted scope exchange keeps the caller until its reply settles.
          await harness.bluetooth.releaseReplies(for: .setFloodScope)
          await #expect(throws: CancellationError.self) { try await ordinary.value }
        } else {
          await harness.bluetooth.releaseReplies(for: .setFloodScope)
          await #expect(throws: MeshCoreError.self) { try await ordinary.value }
        }
        await source.resource.session.waitForQuiescence()
        let messageWasSubmitted = await harness.bluetooth.sentData.contains { $0.first == CommandCode.sendChannelMessage.rawValue }
        #expect(messageWasSubmitted == false)
        await harness.bluetooth.holdReplies(for: .importPrivateKey)
        importing = harness.track(Task {
          try await source.services.nodeConfigService.importConfig(
            MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
            sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
          )
        })
        try await waitUntil("clean response history must permit a real identity exchange on the same session") {
          await IdentityRecoveryFixture.keyWriteCount(harness) == 1
        }
        await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
        await harness.bluetooth.releaseReplies(for: .importPrivateKey)
        try await importing?.value
        #expect(harness.manager.connectedDevice?.radioID == harness.deviceA.radioID)
        #expect(harness.manager.connectedDevice?.publicKey == CanonicalIdentityFixture.publicKey)
        #expect(harness.manager.identityImportRecoveries.isEmpty)
        #expect(try await harness.manager.services?.settingsService.getTime() != nil)
      } catch {
        await harness.bluetooth.releaseAllGates()
        _ = await ordinary.result
        _ = await importing?.result
        throw error
      }
    }
  }

  private func exerciseFailure(
    _ failure: Failure, oldReplyWasOK: Bool, cancelCaller: Bool, settledBeforeAdmission: Bool
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      let radio = failure.usesWiFi ? harness.wifi : harness.bluetooth
      await radio.setChannel(index: 0, name: "Retained", secret: Data(repeating: 0xCD, count: 16))
      try await connect(harness, overWiFi: failure.usesWiFi)
      let source = try #require(harness.manager.installedLink)
      let store = harness.manager.persistenceStore
      let stateA = source.services.settingsService.radioWiFiState
      let stateB = harness.manager.radioWiFiState(for: harness.deviceB.radioID)
      _ = try await source.services.settingsService.runCliCommand("set wifi.enabled 0")
      let gate = IdentityRecoveryFixture.Gate()
      harness.manager.testIdentityBeforeAdmission = { await gate.wait() }
      var importFinished = false
      let importing = harness.track(Task {
        defer { importFinished = true }
        try await source.services.nodeConfigService.importConfig(
          config(name: "Unattributed name", channel: "Unattributed channel"),
          sections: ConfigSections(nodeIdentity: true, channels: true), radioID: harness.deviceA.radioID
        )
      })
      var ordinary: Task<Void, Error>?
      do {
        try await waitUntil("config preflight should finish before the ordinary send") {
          gate.arrived || importFinished
        }
        if !gate.arrived { try await importing.value }
        try #require(gate.arrived)
        let scopesBefore = await radio.sentData.filter { $0.first == CommandCode.setFloodScope.rawValue }.count
        await radio.holdReplies(for: .setFloodScope)
        await radio.holdReplies(for: .sendChannelMessage)
        await radio.setResponsePackets(
          for: .sendChannelMessage,
          packets: [oldReplyWasOK ? Data([ResponseCode.ok.rawValue]) : Data([ResponseCode.error.rawValue, 1])]
        )
        await radio.failNextSendAfterSubmission(.sendChannelMessage, error: failure.error)
        if cancelCaller {
          ordinary = harness.track(Task {
            try await source.resource.session.sendChannelMessage(
              channel: 0, text: "Cancelled ordinary caller", timestamp: Date(), floodScope: .unscoped
            )
          })
        } else {
          let pending = try await source.services.messageService.createPendingChannelMessage(
            text: "Admitted queue worker", channelIndex: 0, radioID: harness.deviceA.radioID
          )
          try await source.services.chatSendQueueService.enqueueChannel(envelope(pending))
        }
        try await waitUntil("the actual channel operation should hold its scope ACK") {
          let scopes = await radio.sentData.filter { $0.first == CommandCode.setFloodScope.rawValue }.count
          let scopeHeld = await radio.heldReplyCount(for: .setFloodScope) >= 1
          return scopes > scopesBefore && scopeHeld
        }
        await radio.holdNextSendCompletion()
        await radio.releaseReplies(for: .setFloodScope)
        try await waitUntil("the channel frame must be physically submitted before its completion fails") {
          let submitted = await radio.sentData.contains { $0.first == CommandCode.sendChannelMessage.rawValue }
          let completionIsHeld = await radio.sendCompletionIsHeld
          return submitted && completionIsHeld
        }
        #expect(await radio.failedSendCount == 0)
        #expect(await radio.isConnected)
        if let ordinary {
          ordinary.cancel()
          // The caller stays suspended until the submitted write settles.
          #expect(await radio.sendCompletionIsHeld)
        }
        if oldReplyWasOK { await radio.rejectNext(.importPrivateKey) }
        await radio.holdReplies(for: .importPrivateKey)
        if settledBeforeAdmission {
          await radio.releaseSendCompletion()
          try await waitUntil("the actual send failure must settle before the early history check") {
            await radio.failedSendCount == 1
          }
          await source.resource.session.waitForQuiescence()
          gate.open()
        } else {
          gate.open()
          try await waitUntil("identity admission must close while the actual old send is still held") {
            await source.services.settingsService.radioWiFiOperationsRetired
          }
          if !cancelCaller {
            try await waitUntil("identity retirement must reach the admitted queue worker") {
              source.services.chatSendQueueService.isRetiredForTesting
            }
          }
          let submittedBeforeFailure = await radio.sentData.contains { $0.first == CommandCode.importPrivateKey.rawValue }
          #expect(submittedBeforeFailure == false)
          #expect(importFinished == false)
          #expect(await radio.sendCompletionIsHeld)
          await radio.releaseSendCompletion()
          if let ordinary {
            _ = await ordinary.result
          }
        }
        try await waitUntil("the actual transport failure must either refuse identity or reveal a key frame") {
          if importFinished { return true }
          return await radio.sentData.contains { $0.first == CommandCode.importPrivateKey.rawValue }
        }
        let keyWasSubmitted = await radio.sentData.contains { $0.first == CommandCode.importPrivateKey.rawValue }
        if keyWasSubmitted, !oldReplyWasOK {
          await radio.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
        }
        await radio.releaseReplies(for: .sendChannelMessage)
        await radio.releaseReplies(for: .importPrivateKey)
        let result = await importing.result
        let importFailed = if case .failure = result { true } else { false }
        let frames = await radio.sentData
        let keyWriteCount = frames.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
        let nameWasSubmitted = frames.contains { $0.first == CommandCode.setName.rawValue }
        let channelTailWasSubmitted = frames.contains { $0.first == CommandCode.setChannel.rawValue }
        #expect(importFailed)
        #expect(keyWriteCount == 0)
        #expect(nameWasSubmitted == false)
        #expect(channelTailWasSubmitted == false)
        #expect(await radio.failedSendCount == 1)
        #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id] == nil)
        #expect(try await store.fetchDevice(id: harness.deviceA.id)?.publicKey == harness.deviceA.publicKey)
        #expect(try await store.fetchDevice(id: harness.deviceA.id)?.radioID == harness.deviceA.radioID)
        let ghostWasPreserved = try await store.fetchDevice(publicKey: CanonicalIdentityFixture.publicKey)?.radioID
          == harness.deviceB.radioID
        #expect(ghostWasPreserved)
        #expect(stateA.rebootPending)
        #expect(stateB.rebootPending == false)
      } catch {
        gate.open()
        await radio.releaseAllGates()
        _ = await ordinary?.result
        _ = await importing.result
        harness.manager.testIdentityBeforeAdmission = nil
        throw error
      }
      harness.manager.testIdentityBeforeAdmission = nil
      await radio.rejectNext(.importPrivateKey, count: 0)
      await radio.setResponsePackets(for: .sendChannelMessage, packets: [Data([ResponseCode.ok.rawValue])])
      try await connect(harness, overWiFi: failure.usesWiFi)
      let fresh = try #require(harness.manager.services)
      let writesBefore = await IdentityRecoveryFixture.keyWriteCount(harness)
      await radio.holdReplies(for: .importPrivateKey)
      let freshImport = harness.track(Task {
        try await fresh.nodeConfigService.importConfig(
          config(name: "Fresh identity name", channel: "Fresh identity channel"),
          sections: ConfigSections(nodeIdentity: true, channels: true), radioID: harness.deviceA.radioID
        )
      })
      do {
        try await waitUntil("a fresh session must submit its own private-key exchange") {
          await IdentityRecoveryFixture.keyWriteCount(harness) == writesBefore + 1
        }
        await radio.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
        await radio.releaseReplies(for: .importPrivateKey)
        try await freshImport.value
      } catch {
        await radio.releaseAllGates()
        _ = await freshImport.result
        throw error
      }
      let current = try #require(harness.manager.services)
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      let canonicalUsesSourceHandle = try await store.fetchDevice(publicKey: CanonicalIdentityFixture.publicKey)?.id
        == harness.deviceA.id
      #expect(canonicalUsesSourceHandle)
      #expect(harness.manager.identityImportRecoveries.isEmpty)
      #expect(current.settingsService.radioWiFiState === stateB)
      #expect(stateB.rebootPending)
      let freshChannelWasSaved = try await store.fetchChannels(radioID: harness.deviceB.radioID)
        .contains { $0.name == "Fresh identity channel" }
      #expect(freshChannelWasSaved)
      #expect(try await current.settingsService.getTime().timeIntervalSince1970 > 0)
      let freshMessage = try await current.messageService.createPendingChannelMessage(
        text: "Fresh identity queue remains live", channelIndex: 0, radioID: harness.deviceB.radioID
      )
      try await current.chatSendQueueService.enqueueChannel(envelope(freshMessage))
      try await waitUntil("the replacement queue must complete a real channel send") {
        await (try? store.fetchMessage(id: freshMessage.id)?.status) == .sent
      }
    }
  }

  private func connect(_ harness: ConnectionRadioHandoffHarness, overWiFi: Bool) async throws {
    if overWiFi {
      await harness.wifi.setDeviceID(harness.deviceA.id)
      _ = try await harness.manager.connectViaWiFi(host: "source.local", port: 4242)
    } else {
      try await harness.connectBluetooth()
    }
  }

  private func config(name: String, channel: String) -> MeshCoreNodeConfig {
    MeshCoreNodeConfig(
      name: name, privateKey: CanonicalIdentityFixture.privateKeyHex,
      channels: [.init(name: channel, secret: String(repeating: "CD", count: 16))]
    )
  }

  private func envelope(_ message: MessageDTO) -> ChannelMessageEnvelope {
    ChannelMessageEnvelope(
      messageID: message.id, channelIndex: 0, isResend: false, messageText: message.text,
      messageTimestamp: message.timestamp, localNodeName: "Provenance radio"
    )
  }

  enum Failure: CaseIterable, Sendable {
    case bluetoothTimeout, bluetoothCancellation, wifiTimeout, wifiSendFailed

    var usesWiFi: Bool {
      self == .wifiTimeout || self == .wifiSendFailed
    }

    var error: any Error {
      switch self {
      case .bluetoothTimeout: BLEError.operationTimeout
      case .bluetoothCancellation: CancellationError()
      case .wifiTimeout: WiFiTransportError.sendTimeout
      case .wifiSendFailed: WiFiTransportError.sendFailed("Synthetic completion failure")
      }
    }
  }

  enum PriorOutcome: CaseIterable, Sendable {
    case cancelledBeforeMessage, rejectedScope
  }
}
