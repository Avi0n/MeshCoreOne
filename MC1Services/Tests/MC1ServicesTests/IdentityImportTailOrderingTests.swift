import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Identity import selected tail ordering")
@MainActor
struct IdentityImportTailOrderingTests {
  @Test
  func `same-secret Ghost history survives the actual planned channel restore before ordinary sync`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness(maxChannels: 2) { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      await harness.bluetooth.setChannel(index: 0, name: "Public", secret: Data(repeating: 0x11, count: 16))
      try await harness.connectBluetooth()
      let store = harness.manager.persistenceStore
      let source = try #require(harness.manager.services)
      let secret = Data(repeating: 0xCD, count: 16)
      let channel = ChannelDTO.testChannel(radioID: harness.deviceB.radioID, index: 1, name: "Restored", secret: secret)
      try await store.saveChannel(channel)
      let history = MessageDTO.testChannelMessage(
        radioID: harness.deviceB.radioID, channelIndex: 1, text: "B existing history"
      )
      try await store.saveMessage(history)
      let pendingB = try await source.messageService.createPendingChannelMessage(
        text: "B saved pending", channelIndex: 1, radioID: harness.deviceB.radioID
      )
      _ = try await store.insertPendingSendAssigningSequence(
        PendingSendDTO(envelope: envelope(pendingB), radioID: harness.deviceB.radioID)
      )
      let pendingA = try await source.messageService.createPendingChannelMessage(
        text: "A saved pending", channelIndex: 0, radioID: harness.deviceA.radioID
      )
      _ = try await store.insertPendingSendAssigningSequence(
        PendingSendDTO(envelope: envelope(pendingA), radioID: harness.deviceA.radioID)
      )
      let initiallyConfigured = await harness.bluetooth.isChannelConfigured(index: 1)
      #expect(!initiallyConfigured)
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      await harness.bluetooth.holdSubmissions(for: .setChannel)
      let contactKey = Data(repeating: 0xC7, count: ProtocolLimits.publicKeySize)
      let config = MeshCoreNodeConfig(
        privateKey: CanonicalIdentityFixture.privateKeyHex,
        channels: [.init(name: "Restored", secret: secret.hexString)],
        contacts: [.init(
          type: 1, name: "Tail contact", publicKey: contactKey.hexString,
          flags: 0, latitude: "0", longitude: "0", lastAdvert: 0, lastModified: 0
        )]
      )
      let importing = harness.track(Task {
        try await source.nodeConfigService.importConfig(
          config, sections: ConfigSections(nodeIdentity: true, channels: true, contacts: true),
          radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("the real planner must reach its selected channel write before physical apply") {
        await harness.bluetooth.submissionIsHeld(for: .setChannel)
      }
      let frames = await harness.bluetooth.sentData
      let identityCommandIndex = frames.firstIndex { $0.first == CommandCode.importPrivateKey.rawValue }
      let keyIndex = try #require(identityCommandIndex)
      let afterIdentity = frames.suffix(from: frames.index(after: keyIndex))
      let noEarlyChannelReads = afterIdentity.filter { $0.first == CommandCode.getChannel.rawValue }.isEmpty
      #expect(noEarlyChannelReads)
      let noEarlyCatchUp = afterIdentity.filter { $0.first == CommandCode.getMessage.rawValue }.isEmpty
      #expect(noEarlyCatchUp)
      let noEarlySends = afterIdentity.filter { $0.first == CommandCode.sendChannelMessage.rawValue }.isEmpty
      #expect(noEarlySends)
      let candidateNotPublished = harness.manager.currentConnection == nil
      #expect(candidateNotPublished)
      let candidateServicesPrivate = harness.manager.services == nil
      #expect(candidateServicesPrivate)
      let candidateSessionPrivate = harness.manager.session == nil
      #expect(candidateSessionPrivate)
      let candidateDevicePrivate = harness.manager.connectedDevice == nil
      #expect(candidateDevicePrivate)
      let historyPreserved = try await store.fetchMessage(id: history.id) != nil
      #expect(historyPreserved)
      let pendingMessagePreserved = try await store.fetchMessage(id: pendingB.id) != nil
      #expect(pendingMessagePreserved)
      let pendingSendPreserved = try await store.fetchPendingSends(radioID: harness.deviceB.radioID).contains { $0.messageID == pendingB.id }
      #expect(pendingSendPreserved)
      let configuredBeforeRestore = await harness.bluetooth.isChannelConfigured(index: 1)
      #expect(!configuredBeforeRestore)
      let noChannelAppliedBeforeRelease = await harness.bluetooth.channelWriteIndices.isEmpty
      #expect(noChannelAppliedBeforeRelease)

      await harness.bluetooth.releaseSubmissions(for: .setChannel)
      try await importing.value

      let canonicalPartitionPublished = harness.manager.connectedDevice?.radioID == harness.deviceB.radioID
      #expect(canonicalPartitionPublished)
      let runtimeReady = harness.manager.connectionState == .ready
      #expect(runtimeReady)
      let plannerUsedPrivateSlot = await harness.bluetooth.channelWriteIndices == [1]
      #expect(plannerUsedPrivateSlot)
      let restored = await harness.bluetooth.channelMatches(index: 1, secret: secret)
      #expect(restored)
      let historyPreserved2 = try await store.fetchMessage(id: history.id) != nil
      #expect(historyPreserved2)
      let pendingMessagePreserved2 = try await store.fetchMessage(id: pendingB.id) != nil
      #expect(pendingMessagePreserved2)
      if try await store.fetchMessage(id: pendingB.id) != nil {
        try await waitUntil("the restored canonical queue should send its preserved pending row") {
          await (try? store.fetchMessage(id: pendingB.id))?.status == .sent
        }
      }
      let canonicalContactSaved = try await store.fetchContacts(radioID: harness.deviceB.radioID).contains { $0.publicKey == contactKey }
      #expect(canonicalContactSaved)
      let noSourceContactSaved = try await store.fetchContacts(radioID: harness.deviceA.radioID).contains { $0.publicKey == contactKey } == false
      #expect(noSourceContactSaved)
      let sourcePendingPreserved = try await store.fetchPendingSends(radioID: harness.deviceA.radioID).contains { $0.messageID == pendingA.id }
      #expect(sourcePendingPreserved)
      let sent = await harness.bluetooth.sentData.filter { $0.first == CommandCode.sendChannelMessage.rawValue }
      let noSourceSend = sent.contains { $0.range(of: Data("A saved pending".utf8)) != nil } == false
      #expect(noSourceSend)
      let noUnconfiguredSend = await harness.bluetooth.unconfiguredChannelSendCount == 0
      #expect(noUnconfiguredSend)
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(laterIOWorks)
    }
  }

  private func envelope(_ message: MessageDTO) -> ChannelMessageEnvelope {
    ChannelMessageEnvelope(
      messageID: message.id, channelIndex: message.channelIndex ?? 0, isResend: false,
      messageText: message.text, messageTimestamp: message.timestamp, localNodeName: "Test Radio"
    )
  }
}
