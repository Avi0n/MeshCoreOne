import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Retirement identity BLE admission")
@MainActor
struct IdentityImportRetirementTests {
  @Test
  func `pre key reservation releases only its first unsent pipeline`() async throws {
    let fixture = try await RetirementBLEManagerHarness.make()
    let gate = Gate()
    var importing: Task<Void, Error>?
    var pipeline: Task<ChannelSyncResult, Error>?
    do {
      let canonicalRadioID = UUID()
      let ghost = DeviceDTO.testDevice(radioID: canonicalRadioID, publicKey: CanonicalIdentityFixture.publicKey)
      try await fixture.manager.persistenceStore.saveDevice(ghost)
      try await fixture.manager.persistenceStore.demoteDeviceToGhost(id: ghost.id)
      try await fixture.connect()
      let source = try #require(fixture.manager.currentConnection)
      let services = try #require(fixture.manager.services)
      let manager = fixture.manager
      await services.nodeConfigService.setIdentityImportHandler { [weak manager, weak services] key in
        await gate.wait()
        guard let manager, let services else { throw CancellationError() }
        return try await manager.importIdentity(key, expectedServices: services, deviceID: source.device.id)
      }
      await fixture.wire.setPublicKey(CanonicalIdentityFixture.publicKey, for: fixture.ble.peripheral.identifier)
      let receipt = Receipt()
      importing = Task {
        defer { receipt.importFinished = true }
        try await services.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: source.device.radioID
        )
      }
      try await waitUntil("real config planning should reach the real manager dispatch boundary") { await gate.isWaiting }
      let before = fixture.ble.native.writePayloads.count
      fixture.ble.native.isReadyToWrite = false
      pipeline = Task {
        try await services.channelService.syncChannels(
          radioID: source.device.radioID, maxChannels: 2, usePipelinedRead: true
        )
      }
      try await fixture.ble.awaitCondition { await fixture.ble.machine.isAwaitingWriteWithoutResponseReady }
      await gate.release()
      let prompt = await fixture.ble.observedBeforeDeadline { await receipt.importFinished }
      #expect(prompt, "Pre-key retirement cannot wait for an unsent ordinary write's readiness watchdog")
      guard prompt else { throw BLESetupRetirementHarness.WaitError.expired }
      try await importing?.value
      _ = await pipeline?.result
      let tail = Array(fixture.ble.native.writePayloads.dropFirst(before))
      let keyOffset = try #require(tail.firstIndex { $0.first == CommandCode.importPrivateKey.rawValue })
      #expect(!tail.prefix(keyOffset).contains { $0.first == CommandCode.getChannel.rawValue })
      #expect(fixture.manager.connectedDevice?.radioID == canonicalRadioID)
      #expect(fixture.manager.currentConnection?.generation != source.generation)
      #expect(await !fixture.ble.machine.isAwaitingWriteWithoutResponseReady)
      #expect(try await fixture.manager.session?.getTime() != nil)
    } catch {
      await gate.release()
      fixture.ble.native.isReadyToWrite = true
      await fixture.ble.machine.shutdown()
      _ = await pipeline?.result
      _ = await importing?.result
      await fixture.close()
      throw error
    }
    await fixture.close()
  }

  @Test(arguments: [false, true])
  func `aborted pipeline prefix must be answered before identity`(answered: Bool) async throws {
    let fixture = try await RetirementBLEManagerHarness.make(maxChannels: 2)
    let gate = Gate()
    var importing: Task<Void, Error>?
    var pipeline: Task<ChannelSyncResult, Error>?
    do {
      let canonicalRadioID = UUID()
      let ghost = DeviceDTO.testDevice(radioID: canonicalRadioID, publicKey: CanonicalIdentityFixture.publicKey)
      try await fixture.manager.persistenceStore.saveDevice(ghost)
      try await fixture.manager.persistenceStore.demoteDeviceToGhost(id: ghost.id)
      try await fixture.connect()
      let sourceSnapshot = fixture.manager.currentConnection
      let source = try #require(sourceSnapshot)
      let services = source.services
      let manager = fixture.manager
      await services.nodeConfigService.setIdentityImportHandler { [weak manager, weak services] key in
        await gate.wait()
        guard let manager, let services else { throw CancellationError() }
        return try await manager.importIdentity(key, expectedServices: services, deviceID: source.device.id)
      }
      await fixture.wire.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      let receipt = Receipt()
      importing = Task {
        defer { receipt.importFinished = true }
        try await services.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: source.device.radioID
        )
      }
      try await waitUntil("normal config planning should reach the real identity dispatch") { await gate.isWaiting }
      let before = fixture.ble.native.writePayloads.count
      await fixture.wire.holdReplies(for: .getChannel)
      fixture.installWriteHandler(backpressureAfterChannelIndex: 0)
      pipeline = Task {
        try await services.channelService.syncChannels(radioID: source.device.radioID, maxChannels: 2, usePipelinedRead: true)
      }
      try await fixture.ble.awaitCondition { await fixture.ble.machine.isAwaitingWriteWithoutResponseReady }
      try await waitUntil("the first actual channel response should remain held") {
        await fixture.wire.heldReplyCount(for: .getChannel) == 1
      }
      let submitted = fixture.ble.native.writePayloads.dropFirst(before)
      let firstOnly = submitted.filter { $0.first == CommandCode.getChannel.rawValue }.map { $0[1] } == [0]
      #expect(firstOnly)
      await gate.release()
      try await fixture.ble.awaitCondition { await !fixture.ble.machine.isAwaitingWriteWithoutResponseReady }
      let noKeyWhilePrefixHeld = !fixture.ble.native.writePayloads.contains {
        $0.first == CommandCode.importPrivateKey.rawValue
      }
      #expect(noKeyWhilePrefixHeld)
      if !answered {
        try await waitUntil("unanswered prefix must refuse identity or expose an unsafe key submission") {
          receipt.importFinished || fixture.ble.native.writePayloads.contains {
            $0.first == CommandCode.importPrivateKey.rawValue
          }
        }
      }
      await fixture.wire.releaseReplies(for: .getChannel)
      let importTask = try #require(importing)
      let result = await importTask.result
      _ = await pipeline?.result
      let keyCount = fixture.ble.native.writePayloads
        .filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
      let importSucceeded = if case .success = result { true } else { false }
      let expectedOutcome = importSucceeded == answered
      let expectedKeyCount = keyCount == (answered ? 1 : 0)
      #expect(expectedOutcome)
      #expect(expectedKeyCount)
      if answered {
        let canonicalReady = fixture.manager.connectedDevice?.radioID == canonicalRadioID && fixture.manager.connectionState == .ready
        #expect(canonicalReady)
      } else {
        let original = try await fixture.manager.persistenceStore.fetchDevice(id: source.device.id)
        let originalPreserved = original?.publicKey == source.device.publicKey && original?.radioID == source.device.radioID
        let canonical = try await fixture.manager.persistenceStore.fetchDevice(publicKey: CanonicalIdentityFixture.publicKey)
        let ghostPreserved = canonical?.radioID == canonicalRadioID && canonical?.connectionMethods.isEmpty == true
        #expect(originalPreserved)
        #expect(ghostPreserved)
        try await fixture.connect()
      }
      let laterIOWorks = try await fixture.manager.session?.getTime() != nil
      #expect(laterIOWorks)
    } catch {
      await gate.release()
      await fixture.wire.releaseAllGates()
      fixture.ble.native.isReadyToWrite = true
      await fixture.ble.machine.shutdown()
      _ = await pipeline?.result
      _ = await importing?.result
      await fixture.close()
      throw error
    }
    await fixture.close()
  }

  @Test
  func `ordinary coalescing reconciles before later identity`() async throws {
    let fixture = try await RetirementBLEManagerHarness.make(maxChannels: 2)
    do {
      await fixture.wire.setChannel(index: 0, name: "First", secret: Data(repeating: 0xC1, count: 16))
      await fixture.wire.setChannel(index: 1, name: "Second", secret: Data(repeating: 0xC2, count: 16))
      try await fixture.connect()
      let sourceSnapshot = fixture.manager.currentConnection
      let source = try #require(sourceSnapshot)
      let before = fixture.ble.native.writePayloads.count
      await fixture.wire.enqueueResponsePackets(for: .getChannel, packets: [])
      let result = try await source.services.channelService.syncChannels(
        radioID: source.device.radioID, maxChannels: 2, usePipelinedRead: true
      )
      let reads = fixture.ble.native.writePayloads.dropFirst(before)
        .filter { $0.first == CommandCode.getChannel.rawValue }.map { $0[1] }
      // The empty reply belongs to whichever primed getChannel the radio handles first.
      // The other can also miss the idle window. Reconcile then repeats the missed indexes in order.
      #expect(reads == [0, 1, 0] || reads == [0, 1, 1] || reads == [0, 1, 0, 1])
      #expect(result.channelsSynced == 2)
      #expect(result.errors.isEmpty)
      await fixture.wire.setIdentityImportPublicKey(CanonicalIdentityFixture.publicKey)
      try await source.services.nodeConfigService.importConfig(
        MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
        sections: ConfigSections(nodeIdentity: true), radioID: source.device.radioID
      )
      #expect(fixture.manager.connectedDevice?.publicKey == CanonicalIdentityFixture.publicKey)
      #expect(fixture.manager.connectionState == .ready)
      let keyWrites = fixture.ble.native.writePayloads.filter { $0.first == CommandCode.importPrivateKey.rawValue }
      #expect(keyWrites.count == 1)
      #expect(try await fixture.manager.session?.getTime() != nil)
    } catch {
      await fixture.wire.releaseAllGates()
      await fixture.close()
      throw error
    }
    await fixture.close()
  }

  @MainActor
  private final class Receipt { var importFinished = false }

  private actor Gate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var released = false
    private(set) var isWaiting = false
    func wait() async {
      guard !released else { return }
      isWaiting = true
      await withCheckedContinuation { continuation = $0 }
      isWaiting = false
    }

    func release() {
      released = true
      continuation?.resume()
      continuation = nil
    }
  }
}
