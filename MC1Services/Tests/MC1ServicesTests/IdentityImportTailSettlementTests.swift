import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Identity import tail settlement")
@MainActor
struct IdentityImportTailSettlementTests {
  @Test
  func `cancelled tail waits for its submitted exchange before canonical activation`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let originalServices = harness.manager.services
      let source = try #require(originalServices)
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      await harness.bluetooth.holdReplies(for: .setChannel)
      var selectedWithReplyHeld: Bool?
      defer { harness.manager.testConnectionSelectionHandler = nil }
      harness.manager.testConnectionSelectionHandler = { _ in
        selectedWithReplyHeld = await harness.bluetooth.heldReplyCount(for: .setChannel) > 0
      }
      let importing = harness.track(Task {
        try await source.nodeConfigService.importConfig(
          config(), sections: ConfigSections(nodeIdentity: true, channels: true, contacts: true),
          radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("the selected channel should be applied with its real reply still held") {
        await harness.bluetooth.heldReplyCount(for: .setChannel) == 1
      }
      importing.cancel()
      await harness.manager.appDidBecomeActive()
      let privateBeforeReply = harness.manager.currentConnection == nil
      let noSelectionBeforeReply = selectedWithReplyHeld == nil
      #expect(privateBeforeReply)
      #expect(noSelectionBeforeReply)
      await harness.bluetooth.releaseReplies(for: .setChannel)
      let result = await importing.result
      let cancelled: Bool = if case let .failure(error) = result { error is CancellationError } else { false }
      let selectionFollowedSettlement = selectedWithReplyHeld == false
      let ready = harness.manager.connectionState == .ready
      let noSkippedContact = try await harness.manager.persistenceStore.fetchContacts(radioID: harness.deviceA.radioID).isEmpty
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(cancelled)
      #expect(selectionFollowedSettlement)
      #expect(ready)
      #expect(noSkippedContact)
      #expect(laterIOWorks)
    }
  }

  @Test
  func `superseding a submitted tail retires its private candidate without publishing it`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness(wifiIsSameRadio: false) { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let originalServices = harness.manager.services
      let source = try #require(originalServices)
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      await harness.bluetooth.holdReplies(for: .setChannel)
      let canonicalRadioID = harness.deviceB.radioID
      var publishedCanonical = false
      harness.manager.onConnectionReady = { snapshot in
        if snapshot.device.radioID == canonicalRadioID { publishedCanonical = true }
      }
      let importing = harness.track(Task {
        try await source.nodeConfigService.importConfig(
          config(), sections: ConfigSections(nodeIdentity: true, channels: true, contacts: true),
          radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("canonical preparation should hold a submitted channel tail") {
        await harness.bluetooth.heldReplyCount(for: .setChannel) == 1
      }
      let sourceRequest = harness.manager.connectionRequestID
      let successor = harness.track(Task { try await harness.manager.connectViaWiFi(host: "successor.local", port: 4242) })
      try await waitUntil("the successor must revoke the captured private candidate") {
        harness.manager.connectionRequestID != sourceRequest
      }
      await harness.bluetooth.releaseReplies(for: .setChannel)
      _ = await importing.result
      _ = try await successor.value
      let successorOwnsPublication = harness.manager.connectedDevice?.publicKey == harness.deviceB.publicKey
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(!publishedCanonical)
      #expect(successorOwnsPublication)
      #expect(laterIOWorks)
    }
  }

  @Test(arguments: [false, true])
  func `final selection failure preserves an earlier tail error and retires the candidate`(tailFails: Bool) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let originalServices = harness.manager.services
      let source = try #require(originalServices)
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      if tailFails { await harness.bluetooth.rejectNext(.updateContact, code: ErrorCode.unsupportedCommand.rawValue) }
      defer { harness.manager.testConnectionSelectionHandler = nil }
      harness.manager.testConnectionSelectionHandler = { _ in throw Failure.selection }
      let importing = harness.track(Task {
        try await source.nodeConfigService.importConfig(
          config(), sections: ConfigSections(nodeIdentity: true, channels: true, contacts: true),
          radioID: harness.deviceA.radioID
        )
      })
      let result = await importing.result
      let expectedError: Bool = if case let .failure(error) = result {
        if tailFails, let contactError = error as? ContactServiceError,
           case let .sessionError(.deviceError(code)) = contactError {
          code == ErrorCode.unsupportedCommand.rawValue
        } else {
          !tailFails && error is Failure
        }
      } else {
        false
      }
      let unpublished = harness.manager.currentConnection == nil
      let retired = await !harness.bluetooth.isConnected
      let canonicalDevice = try await harness.manager.persistenceStore.fetchDevice(publicKey: CanonicalIdentityFixture.publicKey)
      let canonicalSaved = canonicalDevice?.radioID == harness.deviceA.radioID
      let actualChannelApplied = await harness.bluetooth.channelWriteIndices == [0]
      #expect(expectedError)
      #expect(unpublished)
      #expect(retired)
      #expect(canonicalSaved)
      #expect(actualChannelApplied)
      harness.manager.testConnectionSelectionHandler = nil
      _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(laterIOWorks)
    }
  }

  @Test
  func `supersession during final selection cannot publish or retire the successor`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness(wifiIsSameRadio: false) { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let sourceServices = harness.manager.services
      let source = try #require(sourceServices)
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      let selection = IdentityRecoveryFixture.Gate()
      defer {
        selection.open()
        harness.manager.testConnectionSelectionHandler = nil
      }
      var heldRequest: UUID?
      harness.manager.testConnectionSelectionHandler = { requestID in
        guard heldRequest == nil else { return }
        heldRequest = requestID
        await selection.wait()
      }
      let canonicalRadioID = harness.deviceB.radioID
      var publishedCanonical = false
      harness.manager.onConnectionReady = { snapshot in
        if snapshot.device.radioID == canonicalRadioID { publishedCanonical = true }
      }
      let importing = harness.track(Task {
        try await source.nodeConfigService.importConfig(
          config(), sections: ConfigSections(nodeIdentity: true, channels: true, contacts: true),
          radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("the selected tail should finish before final metadata selection") { selection.arrived }
      let successor = harness.track(Task { try await harness.manager.connectViaWiFi(host: "successor.local", port: 4242) })
      try await waitUntil("a new request should supersede held final selection") {
        harness.manager.connectionRequestID != heldRequest
      }
      let noSuccessorDialBeforeMetadataSettles = await harness.wifi.connectCount == 0
      #expect(noSuccessorDialBeforeMetadataSettles)
      selection.open()
      let result = await importing.result
      _ = try await successor.value
      let oldImportFailed = if case .failure = result { true } else { false }
      let successorOwnsPublication = harness.manager.connectedDevice?.publicKey == harness.deviceB.publicKey
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(oldImportFailed)
      #expect(!publishedCanonical)
      #expect(successorOwnsPublication)
      #expect(laterIOWorks)
    }
  }

  @Test
  func `a rejected selected contact still activates the settled canonical channel runtime`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let sourceServices = harness.manager.services
      let source = try #require(sourceServices)
      await harness.bluetooth.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      await harness.bluetooth.rejectNext(.updateContact, code: ErrorCode.unsupportedCommand.rawValue)
      let importing = harness.track(Task {
        try await source.nodeConfigService.importConfig(
          config(), sections: ConfigSections(nodeIdentity: true, channels: true, contacts: true),
          radioID: harness.deviceA.radioID
        )
      })
      let result = await importing.result
      let rejected = if case let .failure(error) = result, let contactError = error as? ContactServiceError,
                        case let .sessionError(.deviceError(code)) = contactError {
        code == ErrorCode.unsupportedCommand.rawValue
      } else {
        false
      }
      let frames = await harness.bluetooth.sentData
      let contactIndex = frames.firstIndex { $0.first == CommandCode.updateContact.rawValue }
      let tailIndex = try #require(contactIndex)
      let firstChannelSync = frames.suffix(from: tailIndex).firstIndex { $0.first == CommandCode.getChannel.rawValue }
      let firstCatchUp = frames.suffix(from: tailIndex).firstIndex { $0.first == CommandCode.getMessage.rawValue }
      let syncFollowedRejection = firstChannelSync != nil && firstCatchUp != nil
      let canonicalReady = harness.manager.connectedDevice?.radioID == harness.deviceB.radioID &&
        harness.manager.connectionState == .ready
      let acceptedChannelRetained = await harness.bluetooth.channelMatches(index: 0, secret: Data(repeating: 0xCD, count: 16))
      let rejectedContactAbsent = try await harness.manager.persistenceStore.fetchContacts(radioID: harness.deviceB.radioID).isEmpty
      let laterIOWorks = try await harness.manager.services?.settingsService.getTime() != nil
      #expect(rejected)
      #expect(syncFollowedRejection)
      #expect(canonicalReady)
      #expect(acceptedChannelRetained)
      #expect(rejectedContactAbsent)
      #expect(laterIOWorks)
    }
  }

  private func config() -> MeshCoreNodeConfig {
    MeshCoreNodeConfig(
      privateKey: CanonicalIdentityFixture.privateKeyHex,
      channels: [.init(name: "Settled", secret: String(repeating: "CD", count: 16))],
      contacts: [.init(
        type: 1, name: "Selected tail", publicKey: Data(repeating: 0xC8, count: 32).hexString,
        flags: 0, latitude: "0", longitude: "0", lastAdvert: 0, lastModified: 0
      )]
    )
  }

  private enum Failure: Error { case selection }
}
