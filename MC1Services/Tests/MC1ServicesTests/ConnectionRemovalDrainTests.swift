import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Destructive removal drains admitted writers")
@MainActor
struct ConnectionRemovalDrainTests {
  private static let sentinel = "admitted-forget-dm"
  /// Long enough for an unfenced delete to finish, short enough to stay a gate observation.
  private static let deletionObservation: Duration = .seconds(1)

  @Test
  func `destructive forget waits for an admitted direct message`() async throws {
    let fixture = try await Fixture.make()
    var removal: Task<ConnectionRemovalResult, Error>?
    do {
      try await fixture.admitSentinel()
      let context = try #require(fixture.env.manager.captureCurrentRemoval())
      var finished = false
      removal = Task {
        let result = try await fixture.env.manager.forgetDevice(context, deleteData: true)
        finished = true
        return result
      }
      try await waitUntil("forget must park in retirement while the admitted save is held") {
        fixture.env.manager.connectionTeardownCount > 0
      }
      try await fixture.observeDeletionStillPending()
      #expect(!finished)
      let messageID = try #require(fixture.gate.messageID)
      fixture.gate.release()
      let outcome = try await #require(removal).value
      if case .removed = outcome {} else { Issue.record("Destructive forget of the live Link must publish removal") }
      try await fixture.expectPartitionAbsent(messageID: messageID)
      try await fixture.expectUnrelatedDataRemains()
      await fixture.finish()
    } catch {
      await fixture.stop(removal)
      throw error
    }
  }

  @Test
  func `admitted successor cannot pass the reserved destructive removal`() async throws {
    let fixture = try await Fixture.make(sameRadio: false)
    var removal: Task<ConnectionRemovalResult, Error>?
    var successor: Task<ConnectionResult, Error>?
    do {
      try await fixture.admitSentinel()
      let context = try #require(fixture.env.manager.captureCurrentRemoval())
      let removedRadioID = fixture.radioID
      removal = Task { try await fixture.env.manager.forgetDevice(context, deleteData: true) }
      try await waitUntil("forget must capture retirement before the successor is admitted") {
        fixture.env.manager.connectionTeardownCount > 0
      }
      let dialsBefore = await fixture.env.wifi.connectCount
      successor = Task { try await fixture.env.connect(.wifi) }
      try await waitUntil("successor must be admitted behind the reserved removal") {
        fixture.env.manager.connectionIntent.wantsConnection && fixture.env.manager.currentConnection == nil
      }
      try await fixture.observeDeletionStillPending()
      #expect(await fixture.env.wifi.connectCount == dialsBefore)
      #expect(fixture.env.manager.currentConnection == nil)
      let messageID = try #require(fixture.gate.messageID)
      fixture.gate.release()
      _ = try await removal?.value
      let connected = try await #require(successor).value
      #expect(fixture.env.manager.isCurrent(connected))
      #expect(try await fixture.env.manager.session?.getTime() != nil)
      #expect(try await fixture.env.manager.persistenceStore.fetchDevice(radioID: removedRadioID) == nil)
      #expect(try await fixture.env.manager.persistenceStore.fetchMessage(id: messageID) == nil)
      let contacts = try await fixture.env.manager.persistenceStore.fetchContacts(radioID: removedRadioID)
      #expect(contacts.isEmpty)
      try await fixture.expectUnrelatedDataRemains()
      await fixture.finish()
    } catch {
      await fixture.stop(removal, successor)
      throw error
    }
  }

  @Test
  func `accepted confirmation after supersession still drains the captured retirement`() async throws {
    let fixture = try await Fixture.make(sameRadio: false)
    let confirmation = ConfirmationGate()
    var removal: Task<ConnectionRemovalResult, Error>?
    var successor: Task<ConnectionResult, Error>?
    do {
      try await fixture.admitSentinel()
      let device = try #require(fixture.env.manager.connectedDevice)
      fixture.armConfirmation(confirmation, accessoryID: device.id)
      let context = try #require(fixture.env.manager.captureCurrentRemoval())
      removal = Task { try await fixture.env.manager.forgetDevice(context, deleteData: true) }
      try await waitUntil("system confirmation must be waiting") { confirmation.entered }
      let previousRequest = fixture.env.manager.connectionRequestID
      successor = Task { try await fixture.env.connect(.wifi) }
      try await waitUntil("successor retirement must park on the admitted writer") {
        let dials = await fixture.env.wifi.connectCount
        return fixture.env.manager.connectionRequestID != previousRequest && fixture.env.manager.connectionTeardownCount > 0
          && dials == 0
      }
      let successorRequest = fixture.env.manager.connectionRequestID
      let lossesBefore = fixture.losses.count
      confirmation.release()
      try await waitUntil("accepted confirmation must reserve removal without retiring the successor") {
        fixture.env.pairing.accessory(for: device.id) == nil && fixture.env.manager.connectionRequestID == successorRequest
      }
      try await fixture.observeDeletionStillPending()
      #expect(fixture.env.manager.connectionRequestID == successorRequest)
      #expect(fixture.env.manager.connectionIntent.wantsConnection)
      #expect(fixture.losses.count == lossesBefore)
      #expect(await fixture.env.wifi.connectCount == 0)
      let messageID = try #require(fixture.gate.messageID)
      fixture.gate.release()
      let outcome = try await #require(removal).value
      if case .superseded = outcome {} else { Issue.record("A detached confirmation must not publish removal into the successor") }
      let connected = try await #require(successor).value
      #expect(fixture.env.manager.isCurrent(connected))
      #expect(fixture.losses.count == lossesBefore)
      #expect(try await fixture.env.manager.session?.getTime() != nil)
      try await fixture.expectPartitionAbsent(messageID: messageID)
      await fixture.finish()
    } catch {
      confirmation.release()
      await fixture.stop(removal, successor)
      throw error
    }
  }

  @Test
  func `new pairing during a held destructive removal keeps the fresh association`() async throws {
    let fixture = try await Fixture.make(sameRadio: false)
    let confirmation = ConfirmationGate()
    var removal: Task<ConnectionRemovalResult, Error>?
    var successor: Task<ConnectionResult, Error>?
    do {
      try await fixture.admitSentinel()
      let device = try #require(fixture.env.manager.connectedDevice)
      fixture.armConfirmation(confirmation, accessoryID: device.id)
      let context = try #require(fixture.env.manager.captureCurrentRemoval())
      removal = Task { try await fixture.env.manager.forgetDevice(context, deleteData: true) }
      try await waitUntil("system confirmation must be waiting") { confirmation.entered }
      successor = Task { try await fixture.env.connect(.wifi) }
      try await waitUntil("successor must supersede the removal source") {
        fixture.env.manager.connectionRequestID != context.requestID && fixture.env.manager.connectionTeardownCount > 0
      }
      confirmation.release()
      try await waitUntil("accepted confirmation must queue removal while the admitted save is held") {
        fixture.env.pairing.accessory(for: device.id) == nil && fixture.gate.messageID != nil
      }
      try await fixture.observeDeletionStillPending()
      fixture.env.pairing.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: device.id, displayName: "Renewed association")
      ])
      #expect(fixture.env.manager.pairing.isDeviceConnectable(device.id))
      fixture.gate.release()
      _ = try await removal?.value
      _ = try await successor?.value
      let preserved = try #require(try await fixture.env.manager.persistenceStore.fetchDevice(id: device.id))
      #expect(preserved.radioID == device.radioID)
      #expect(try await fixture.env.manager.session?.getTime() != nil)
      await fixture.finish()
    } catch {
      confirmation.release()
      await fixture.stop(removal, successor)
      throw error
    }
  }

  @Test
  func `drained retirement still deletes and a missing source stays absent`() async throws {
    let fixture = try await Fixture.make(connect: false)
    do {
      let saved = DeviceDTO.testDevice(publicKey: Data(repeating: 0x51, count: 32), isActive: false)
      try await fixture.env.manager.persistenceStore.saveDevice(saved)
      #expect(fixture.env.manager.installedLink == nil)
      #expect(fixture.env.manager.retirementTask == nil)
      let context = try await fixture.env.manager.captureRemoval(id: saved.id)
      let removed = try await fixture.env.manager.forgetDevice(context, deleteData: true)
      if case .superseded = removed {} else { Issue.record("Removal without a Link has no loss to publish") }
      #expect(try await fixture.env.manager.persistenceStore.fetchDevice(id: saved.id) == nil)

      let missing = DeviceDTO.testDevice(publicKey: Data(repeating: 0x52, count: 32), isActive: false)
      try await fixture.env.manager.persistenceStore.saveDevice(missing)
      let stale = try await fixture.env.manager.captureRemoval(id: missing.id)
      try await fixture.env.manager.persistenceStore.deleteDevice(id: missing.id)
      _ = try await fixture.env.manager.forgetDevice(stale, deleteData: true)
      #expect(try await fixture.env.manager.persistenceStore.fetchDevice(id: missing.id) == nil)
      #expect(try await fixture.env.manager.persistenceStore.fetchDevice(radioID: missing.radioID) == nil)
      await fixture.finish()
    } catch {
      await fixture.finish()
      throw error
    }
  }

  @Test
  func `ghost demotion does not wait for the admitted writer`() async throws {
    let fixture = try await Fixture.make()
    var removal: Task<ConnectionRemovalResult, Error>?
    do {
      try await fixture.admitSentinel()
      let device = try #require(fixture.env.manager.connectedDevice)
      let context = try #require(fixture.env.manager.captureCurrentRemoval())
      var finished = false
      removal = Task {
        let result = try await fixture.env.manager.forgetDevice(context, deleteData: false)
        finished = true
        return result
      }
      try await waitUntil("ghost demotion must finish while the admitted save is still held") {
        await (try? fixture.env.manager.persistenceStore.fetchDevice(id: device.id)) == nil
      }
      #expect(!finished)
      let ghost = try #require(try await fixture.env.manager.persistenceStore.fetchDevice(radioID: device.radioID))
      #expect(ghost.id != device.id)
      #expect(!ghost.isActive)
      #expect(ghost.connectionMethods.isEmpty)
      fixture.gate.release()
      _ = await removal?.result
      await fixture.finish()
    } catch {
      await fixture.stop(removal)
      throw error
    }
  }

  @MainActor
  private final class Fixture {
    let env: ConnectionManagerRadioTestHarness
    let gate = SaveGate()
    let radioID: UUID
    let unrelatedDeviceID: UUID
    let unrelatedContactID: UUID
    let unrelatedMessageID: UUID
    private(set) var losses: [ConnectionLossReceipt] = []
    private let coordinator: SyncCoordinator?

    private init(
      env: ConnectionManagerRadioTestHarness,
      radioID: UUID,
      unrelatedDeviceID: UUID,
      unrelatedContactID: UUID,
      unrelatedMessageID: UUID,
      coordinator: SyncCoordinator?
    ) {
      self.env = env
      self.radioID = radioID
      self.unrelatedDeviceID = unrelatedDeviceID
      self.unrelatedContactID = unrelatedContactID
      self.unrelatedMessageID = unrelatedMessageID
      self.coordinator = coordinator
    }

    static func make(sameRadio: Bool = true, connect: Bool = true) async throws -> Fixture {
      let env = try ConnectionManagerRadioTestHarness(sameRadio: sameRadio)
      let unrelatedDeviceID = UUID()
      let unrelatedContactID = UUID()
      let unrelatedMessageID = UUID()
      let unrelatedRadioID = UUID()
      do {
        let store = env.manager.persistenceStore
        try await store.saveDevice(DeviceDTO.testDevice(id: unrelatedDeviceID, radioID: unrelatedRadioID, publicKey: Data(repeating: 0x44, count: 32), isActive: false))
        try await store.saveContact(ContactDTO.testContact(id: unrelatedContactID, radioID: unrelatedRadioID, publicKey: Data(repeating: 0x45, count: 32)))
        try await store.saveMessage(Self.storedMessage(id: unrelatedMessageID, radioID: unrelatedRadioID, contactID: unrelatedContactID))
        var radioID = UUID()
        var coordinator: SyncCoordinator?
        if connect {
          _ = try await env.connect(.bluetooth)
          let device = try #require(env.manager.connectedDevice)
          radioID = device.radioID
          let contact = ContactDTO.testContact(
            radioID: radioID, publicKey: Data(repeating: 0xAB, count: 32), name: "Dispatch"
          )
          try await store.saveContact(contact)
          let sync = try #require(env.manager.services).syncCoordinator
          coordinator = sync
          await sync.setTestBeforeMessageSave { message in
            await SaveGate.sharedHold(message)
          }
        }
        let fixture = Fixture(
          env: env, radioID: radioID, unrelatedDeviceID: unrelatedDeviceID,
          unrelatedContactID: unrelatedContactID, unrelatedMessageID: unrelatedMessageID, coordinator: coordinator
        )
        SaveGate.current = fixture.gate
        fixture.env.manager.onConnectionLost = { receipt in
          fixture.losses.append(receipt)
        }
        return fixture
      } catch {
        await env.close()
        throw error
      }
    }

    func admitSentinel() async throws {
      let contacts = try await env.manager.persistenceStore.fetchContacts(radioID: radioID)
      let contact = try #require(contacts.first { $0.name == "Dispatch" })
      await env.ble.inject(Self.directPacket(prefix: contact.publicKey, text: ConnectionRemovalDrainTests.sentinel))
      try await waitUntil("the admitted direct message must reach the real save hook") { self.gate.messageID != nil }
    }

    func observeDeletionStillPending() async throws {
      let probe = Probe()
      _ = env.manager.enqueueMetadata { _ in
        probe.started = true
      }
      let deadline = ContinuousClock.now + ConnectionRemovalDrainTests.deletionObservation
      while ContinuousClock.now < deadline, !probe.started {
        try await Task.sleep(for: .milliseconds(20))
      }
      #expect(!probe.started)
      #expect(try await env.manager.persistenceStore.fetchDevice(radioID: radioID) != nil)
    }

    func armConfirmation(_ confirmation: ConfirmationGate, accessoryID: UUID) {
      env.pairing.isSessionActive = true
      env.pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: accessoryID, displayName: "Radio")])
      env.pairing.beforeRemovalResult = { await confirmation.wait() }
    }

    func expectPartitionAbsent(messageID: UUID) async throws {
      #expect(try await env.manager.persistenceStore.fetchMessage(id: messageID) == nil)
      #expect(try await env.manager.persistenceStore.fetchDevice(radioID: radioID) == nil)
      let contacts = try await env.manager.persistenceStore.fetchContacts(radioID: radioID)
      #expect(contacts.isEmpty)
    }

    func expectUnrelatedDataRemains() async throws {
      #expect(try await env.manager.persistenceStore.fetchDevice(id: unrelatedDeviceID) != nil)
      #expect(try await env.manager.persistenceStore.fetchContact(id: unrelatedContactID) != nil)
      #expect(try await env.manager.persistenceStore.fetchMessage(id: unrelatedMessageID) != nil)
    }

    func stop(_ first: Task<ConnectionRemovalResult, Error>?, _ second: Task<ConnectionResult, Error>? = nil) async {
      gate.release()
      _ = await first?.result
      _ = await second?.result
      await finish()
    }

    func finish() async {
      gate.release()
      if let coordinator {
        await coordinator.setTestBeforeMessageSave(nil)
      }
      SaveGate.current = nil
      await env.close()
    }

    private static func directPacket(prefix: Data, text: String) -> Data {
      var packet = Data([ResponseCode.contactMessageReceived.rawValue])
      packet.append(prefix.prefix(6))
      packet.append(0)
      packet.append(MeshCore.TextType.plainText.rawValue)
      let timestamp = UInt32(Date().timeIntervalSince1970)
      packet.append(withUnsafeBytes(of: timestamp.littleEndian) { Data($0) })
      packet.append(contentsOf: text.utf8)
      return packet
    }

    private static func storedMessage(id: UUID, radioID: UUID, contactID: UUID) -> MessageDTO {
      let now = Date()
      return MessageDTO(
        id: id, radioID: radioID, contactID: contactID, channelIndex: nil, text: "unrelated",
        timestamp: UInt32(now.timeIntervalSince1970), createdAt: now, direction: .incoming, status: .delivered,
        textType: .plain, ackCode: nil, pathLength: 0, snr: nil, senderKeyPrefix: nil, senderNodeName: nil,
        isRead: true, replyToID: nil, roundTripTime: nil, heardRepeats: 0, retryAttempt: 0, maxRetryAttempts: 0
      )
    }
  }

  /// Parked across monitor cancellation so the admitted save cannot be skipped.
  @MainActor
  private final class SaveGate {
    static var current: SaveGate?
    private(set) var messageID: UUID?
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    static func sharedHold(_ message: MessageDTO) async {
      await current?.hold(message)
    }

    func hold(_ message: MessageDTO) async {
      guard message.text == ConnectionRemovalDrainTests.sentinel else { return }
      messageID = message.id
      guard !isOpen else { return }
      await withCheckedContinuation { continuation in
        if isOpen {
          continuation.resume()
        } else {
          waiters.append(continuation)
        }
      }
    }

    func release() {
      isOpen = true
      let pending = waiters
      waiters.removeAll()
      for waiter in pending {
        waiter.resume()
      }
    }
  }

  @MainActor
  private final class Probe {
    var started = false
  }

  @MainActor
  private final class ConfirmationGate {
    private(set) var entered = false
    private var released = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
      entered = true
      guard !released else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func release() {
      released = true
      continuation?.resume()
      continuation = nil
    }
  }
}
