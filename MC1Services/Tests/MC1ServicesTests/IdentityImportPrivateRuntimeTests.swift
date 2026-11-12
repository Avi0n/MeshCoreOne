import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Identity import private runtime ownership")
@MainActor
struct IdentityImportPrivateRuntimeTests {
  @Test(arguments: [false, true])
  func `actual WiFi receive loss revokes a private candidate before publication`(atSelection: Bool) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      _ = try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242)
      let sourceServices = harness.manager.services
      let source = try #require(sourceServices)
      await harness.wifi.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      let selection = IdentityRecoveryFixture.Gate()
      defer {
        selection.open()
        harness.manager.testConnectionSelectionHandler = nil
      }
      if atSelection {
        harness.manager.testConnectionSelectionHandler = { _ in await selection.wait() }
      } else {
        await harness.wifi.holdSubmissions(for: .setChannel)
      }
      let canonicalRadioID = harness.deviceB.radioID
      var publishedCanonical = false
      harness.manager.onConnectionReady = { snapshot in
        if snapshot.device.radioID == canonicalRadioID { publishedCanonical = true }
      }
      let importing = harness.track(Task {
        try await source.nodeConfigService.importConfig(
          MeshCoreNodeConfig(
            privateKey: CanonicalIdentityFixture.privateKeyHex,
            channels: [.init(name: "Selected tail", secret: String(repeating: "CD", count: 16))]
          ), sections: ConfigSections(nodeIdentity: true, channels: true), radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("canonical WiFi preparation should reach its private boundary") {
        if atSelection { return selection.arrived }
        return await harness.wifi.submissionIsHeld(for: .setChannel)
      }
      let candidateAttempt = harness.manager.desiredRequest?.attempt
      let candidate = try #require(candidateAttempt)
      let unpublishedBeforeLoss = harness.manager.currentConnection == nil
      let activeBeforeLoss = candidate.binding.isActive
      #expect(unpublishedBeforeLoss)
      #expect(harness.manager.identityRebuildUnpublished)
      #expect(activeBeforeLoss)

      await harness.wifi.simulateSocketReceiveLoss()

      let revokedByCallback = !candidate.binding.isActive
      let unpublishedAfterLoss = harness.manager.currentConnection == nil
      #expect(revokedByCallback)
      #expect(unpublishedAfterLoss)
      selection.open()
      await harness.wifi.releaseSubmissions(for: .setChannel)
      let result = await importing.result
      let failed = if case .failure = result { true } else { false }
      let stillUnpublished = harness.manager.currentConnection == nil
      let disconnected = await !harness.wifi.isConnected
      let candidateRetired = candidate.preparedServices?.isAcceptingRuntimeWork == false
      let frames = await harness.wifi.sentData
      let keyIndex = frames.firstIndex { $0.first == CommandCode.importPrivateKey.rawValue }
      let index = try #require(keyIndex)
      let ordinaryWorkSuppressed = !frames.suffix(from: index).contains {
        $0.first == CommandCode.getChannel.rawValue || $0.first == CommandCode.getMessage.rawValue ||
          $0.first == CommandCode.sendChannelMessage.rawValue
      }
      let actualTailApplied = await harness.wifi.channelWriteIndices == (atSelection ? [0] : [])
      #expect(failed)
      #expect(stillUnpublished)
      #expect(!harness.manager.identityRebuildUnpublished)
      #expect(disconnected)
      #expect(candidateRetired)
      #expect(!publishedCanonical)
      #expect(ordinaryWorkSuppressed)
      #expect(actualTailApplied)

      harness.manager.testConnectionSelectionHandler = nil
      _ = try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242)
      let replacementReady = harness.manager.connectionState == .ready
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(replacementReady)
      #expect(laterIOWorks)
    }
  }

  @Test(arguments: [false, true])
  func `late imported setup completion cannot disconnect the next BLE attempt in the same request`(
    explicitRecovery: Bool
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      await harness.manager.wireTransportHandlers()
      let sourceServices = harness.manager.services
      let source = try #require(sourceServices)
      let recovery: IdentityImportRecovery? = if explicitRecovery { try await IdentityRecoveryFixture.interrupt(harness) } else { nil }
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      let setup = IdentityRecoveryFixture.Gate()
      var completionHeld = false
      var lossHandled = false
      defer {
        setup.open()
        harness.manager.testBLELifecycleEventHandled = nil
        harness.manager.testPostSetupResultHandler = nil
      }
      harness.manager.testPostSetupResultHandler = { _ in
        guard !completionHeld else { return }
        completionHeld = true
        await setup.wait()
      }
      harness.manager.testBLELifecycleEventHandled = { event in
        if case .autoReconnecting = event { lossHandled = true }
      }
      let importing = harness.track(Task {
        if let recovery {
          _ = try await harness.manager.retryIdentityImport(confirmation: recovery, privateKey: CanonicalIdentityFixture.privateKey)
        } else {
          try await source.nodeConfigService.importConfig(
            MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
            sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
          )
        }
      })
      try await waitUntil("the imported Link should finish setup before its caller continuation") { setup.arrived }
      let requestID = harness.manager.connectionRequestID
      let importedGeneration = harness.manager.currentConnection?.generation
      let oldAttempt = harness.manager.desiredRequest?.attempt
      let old = try #require(oldAttempt)
      await harness.bluetooth.simulateAutoReconnecting(deviceID: harness.deviceA.id)
      try await waitUntil("native BLE loss should finish captured retirement") { lossHandled }
      await harness.bluetooth.simulateReconnected()
      try await waitUntil("same-request BLE recovery should install a new ready attempt") {
        harness.manager.currentConnection?.generation != importedGeneration && harness.manager.connectionState == .ready
      }
      let successorSnapshot = harness.manager.currentConnection
      let successor = try #require(successorSnapshot)
      let nextAttempt = harness.manager.desiredRequest?.attempt
      let next = try #require(nextAttempt)
      let sameRequest = harness.manager.connectionRequestID == requestID
      let distinctAttempt = next !== old
      #expect(sameRequest)
      #expect(distinctAttempt)
      setup.open()
      let result = await importing.result
      let oldImportFailed = if case .failure = result { true } else { false }
      let successorStillCurrent = harness.manager.isCurrent(successor)
      let successorActive = next.binding.isActive
      let requestStillCurrent = harness.manager.connectionRequestID == requestID
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(oldImportFailed)
      #expect(successorStillCurrent)
      #expect(successorActive)
      #expect(requestStillCurrent)
      #expect(laterIOWorks)
    }
  }

  @Test(arguments: [false, true])
  func `ordinary and standalone setup hydrate before wiring even when initial contacts fail`(standalone: Bool) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      if standalone { try await harness.connectBluetooth() }
      let sourceServices = harness.manager.services
      let store = harness.manager.persistenceStore
      let secret = Data(repeating: 0xCD, count: 16)
      await harness.bluetooth.setChannel(index: 0, name: "Hydrated", secret: secret)
      try await store.saveChannel(ChannelDTO.testChannel(radioID: harness.deviceA.radioID, name: "Hydrated", secret: secret))
      let message = MessageDTO.testChannelMessage(
        radioID: harness.deviceA.radioID, text: "Retained before initial sync", status: .pending
      )
      try await store.saveMessage(message)
      let envelope = ChannelMessageEnvelope(
        messageID: message.id, channelIndex: 0, isResend: false, messageText: message.text,
        messageTimestamp: message.timestamp, localNodeName: "Hydration control"
      )
      _ = try await store.insertPendingSendAssigningSequence(PendingSendDTO(envelope: envelope, radioID: harness.deviceA.radioID))
      await harness.bluetooth.rejectNext(.getContacts)
      var hydratedBeforeWiring = false
      harness.manager.onConnectionReady = { _ in
        do {
          try await waitUntil("hydrated work should enter its real send before initial sync") {
            await (try? store.fetchMessage(id: message.id))?.status == .sent
          }
          hydratedBeforeWiring = true
        } catch {}
      }
      if standalone {
        let source = try #require(sourceServices)
        await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
        _ = try await harness.manager.importIdentity(
          CanonicalIdentityFixture.privateKey, expectedServices: source, deviceID: harness.deviceA.id
        )
      } else {
        _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      }
      let retainedWhileSyncing = harness.manager.connectionState == .syncing
      let pendingSettled = try await store.fetchPendingSends(radioID: harness.deviceA.radioID).isEmpty
      #expect(hydratedBeforeWiring)
      #expect(retainedWhileSyncing)
      #expect(pendingSettled)
      try await waitUntil(timeout: .seconds(12), "ordinary retry should retain and recover the runtime") {
        harness.manager.connectionState == .ready
      }
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(laterIOWorks)
    }
  }
}
