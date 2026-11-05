import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing
import UserNotifications

@Suite("Advertisement cleanup contract")
@MainActor
struct AdvertisementCleanupContractTests {
  @Test(arguments: [false, true])
  func `committed deletion removes captured notifications without a presentation subscriber`(foreground: Bool) async throws {
    let fixture = try await Fixture(foreground: foreground)
    do {
      let contact = try await fixture.addContact(seed: 41)
      fixture.recorder.addRequests(contactID: contact.id, prefix: "deleted")
      fixture.recorder.addRequests(contactID: UUID(), prefix: "unrelated")
      await fixture.transport.inject(Data([ResponseCode.contactDeleted.rawValue]) + contact.publicKey)
      try await fixture.waitUntil {
        if foreground {
          return try await fixture.store.fetchContact(id: contact.id) == nil
        }
        return await fixture.service.pendingDeletedKeys.contains(contact.publicKey)
      }

      await fixture.service.stopEventMonitoring()

      #expect(try await fixture.store.fetchContact(id: contact.id) == nil)
      #expect(try await fixture.store.fetchMessages(contactID: contact.id, limit: 10, offset: 0).isEmpty)
      #expect(Set(fixture.recorder.removed) == ["deleted-dm", "deleted-reaction"])
      #expect(fixture.recorder.requests.map(\.identifier).sorted() == ["unrelated-dm", "unrelated-reaction"])
      #expect(fixture.recorder.badges.isEmpty)
      _ = try await fixture.session.getTime()
    } catch {
      await fixture.cleanup()
      throw error
    }
    await fixture.cleanup()
  }

  @Test(arguments: [false, true])
  func `held system cleanup does not block deletion admission or trace presentation`(deferred: Bool) async throws {
    let fixture = try await Fixture(foreground: !deferred)
    let first = try await fixture.addContact(seed: 42)
    let second = try await fixture.addContact(seed: 43)
    fixture.recorder.addRequests(contactID: first.id, prefix: "first")
    fixture.recorder.addRequests(contactID: second.id, prefix: "second")
    fixture.recorder.addRequests(contactID: UUID(), prefix: "unrelated")
    fixture.recorder.holdNextSnapshot = true
    let events = fixture.service.events()
    let receipt = Receipt()
    let listener = Task {
      for await event in events {
        if case .traceResponse = event { receipt.traceSeen = true }
      }
    }
    var flush: Task<Void, Never>?
    var stop: Task<Void, Never>?
    do {
      await fixture.transport.inject(Data([ResponseCode.contactDeleted.rawValue]) + first.publicKey)
      if deferred {
        try await fixture.waitUntil { await fixture.service.pendingDeletedKeys.contains(first.publicKey) }
        await fixture.scene.setIsInForeground(true)
        flush = Task { await fixture.service.handleReturnToForeground() }
      }
      try await fixture.waitUntil { fixture.recorder.snapshotIsHeld }
      #expect(try await fixture.store.fetchContact(id: first.id) == nil)
      if deferred { await fixture.scene.setIsInForeground(false) }
      await fixture.transport.inject(Data([ResponseCode.contactDeleted.rawValue]) + second.publicKey)
      var trace = Data([ResponseCode.traceData.rawValue, 0, 0, 0])
      for value: UInt32 in [999, 888] {
        trace.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
      }
      trace.append(0x28)
      await fixture.transport.inject(trace)
      try await fixture.waitUntil {
        await fixture.service.pendingDeletedKeys.contains(second.publicKey) && receipt.traceSeen
      }
      #expect(try await fixture.store.fetchContact(id: second.id) != nil)
      stop = Task {
        await fixture.service.stopEventMonitoring()
        receipt.stopped = true
      }
      let endedEarly = await fixture.observedWithin(.milliseconds(80)) { receipt.stopped }
      #expect(!endedEarly)
      fixture.recorder.releaseSnapshot()
      await flush?.value
      await stop?.value
      await fixture.service.stopEventMonitoring()
      #expect(try await fixture.store.fetchContact(id: second.id) == nil)
      #expect(Set(fixture.recorder.removed) == ["first-dm", "first-reaction", "second-dm", "second-reaction"])
      #expect(fixture.recorder.removed.count == 4)
      #expect(fixture.recorder.requests.map(\.identifier).sorted() == ["unrelated-dm", "unrelated-reaction"])
      #expect(fixture.recorder.badges.isEmpty)
      _ = try await fixture.session.getTime()
    } catch {
      fixture.recorder.releaseSnapshot()
      await flush?.value
      await fixture.cleanup()
      await stop?.value
      await listener.value
      throw error
    }
    await fixture.cleanup()
    await listener.value
  }

  @Test(arguments: DeletionControl.allCases)
  func `uncommitted or skipped deletion removes no notification`(control: DeletionControl) async throws {
    let mock = MockPersistenceStore()
    let fixture = try await Fixture(foreground: false, store: mock)
    var flush: Task<Void, Never>?
    do {
      let contact = try await fixture.addContact(seed: 44)
      fixture.recorder.addRequests(contactID: contact.id, prefix: "preserved")
      let key = control == .empty ? Data(repeating: 45, count: 32) : contact.publicKey
      await fixture.transport.inject(Data([ResponseCode.contactDeleted.rawValue]) + key)
      try await fixture.waitUntil { await fixture.service.pendingDeletedKeys.contains(key) }
      if control == .failed { await mock.setStubbedDeleteContactError(ControlError.storeUnavailable) }
      if control == .revived { await mock.holdNextDeleteContacts() }
      await fixture.scene.setIsInForeground(true)
      flush = Task { await fixture.service.handleReturnToForeground() }
      if control == .revived {
        try await fixture.waitUntil { await mock.isDeleteContactsHeld }
        await fixture.transport.inject(Data([ResponseCode.advertisement.rawValue]) + key)
        try await fixture.waitUntil { await fixture.service.pendingAdvertKeys.contains(key) }
        await mock.releaseDeleteContacts()
      }
      await flush?.value
      await fixture.service.stopEventMonitoring()
      #expect(fixture.recorder.removed.isEmpty)
      #expect(fixture.recorder.loads == 0)
      #expect(try await fixture.store.fetchContact(id: contact.id) != nil)
      _ = try await fixture.session.getTime()
    } catch {
      await mock.releaseDeleteContacts()
      await flush?.value
      await fixture.cleanup()
      throw error
    }
    await fixture.cleanup()
  }

  enum DeletionControl: CaseIterable, Sendable { case failed, empty, revived }
  private enum ControlError: Error { case storeUnavailable, expired }

  @MainActor
  private final class Receipt {
    var traceSeen = false
    var stopped = false
  }

  @MainActor
  private final class Fixture {
    let radioID = UUID()
    let transport = ConnectionTestRadioTransport()
    let session: MeshCoreSession
    let store: any PersistenceStoreProtocol
    let scene: MockAppStateProvider
    let service: AdvertisementService
    let recorder = Recorder()
    let defaults: UserDefaults
    let suite = "test.cleanup-contract.\(UUID())"

    init(foreground: Bool, store suppliedStore: (any PersistenceStoreProtocol)? = nil) async throws {
      defaults = try #require(UserDefaults(suiteName: suite))
      if let suppliedStore {
        store = suppliedStore
      } else {
        store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
      }
      scene = MockAppStateProvider(isInForeground: foreground)
      session = MeshCoreSession(transport: transport)
      let notificationService = NotificationService(notificationDefaults: defaults, systemOperations: recorder.operations)
      let contacts = ContactService(session: session, dataStore: store, syncCoordinator: nil, cleanupCoordinator: nil)
      service = AdvertisementService(
        session: session, dataStore: store, contactService: contacts,
        appStateProvider: scene, notificationService: notificationService
      )
      try await session.start()
      await service.startEventMonitoring(radioID: radioID)
      try await waitUntil { await self.session.subscriberCountForTest > 0 }
    }

    func addContact(seed: UInt8) async throws -> ContactDTO {
      let frame = ContactFrame(
        publicKey: Data(repeating: seed, count: 32), type: .chat, flags: 0,
        outPathLength: 0, outPath: Data(), name: "Contact", lastAdvertTimestamp: 0,
        latitude: 0, longitude: 0, lastModified: 0
      )
      let saved = try await store.saveContact(radioID: radioID, from: frame)
      try await store.saveMessage(MessageDTO.testDirectMessage(radioID: radioID, contactID: saved.id, text: "message"))
      return try #require(await store.fetchContact(id: saved.id))
    }

    func waitUntil(_ predicate: @MainActor () async throws -> Bool) async throws {
      guard try await observedWithin(.seconds(1), predicate) else { throw ControlError.expired }
    }

    func observedWithin(_ duration: Duration, _ predicate: @MainActor () async throws -> Bool) async rethrows -> Bool {
      let deadline = ContinuousClock.now.advanced(by: duration)
      while try await !predicate() {
        guard ContinuousClock.now < deadline else { return false }
        try? await Task.sleep(for: .milliseconds(1))
      }
      return true
    }

    func cleanup() async {
      recorder.releaseSnapshot()
      await service.stopEventMonitoring()
      service.finishEvents()
      await session.stop()
      await session.waitForQuiescence()
      #expect(await session.subscriberCountForTest == 0)
      defaults.removePersistentDomain(forName: suite)
    }
  }

  @MainActor
  private final class Recorder {
    var requests: [UNNotificationRequest] = []
    var removed: [String] = []
    var badges: [Int] = []
    var loads = 0
    var holdNextSnapshot = false
    var snapshotIsHeld = false
    var snapshotWaiter: CheckedContinuation<Void, Never>?

    func addRequests(contactID: UUID, prefix: String) {
      for type in ["dm", "reaction"] {
        let content = UNMutableNotificationContent()
        content.userInfo = ["contactID": contactID.uuidString, "type": type]
        requests.append(UNNotificationRequest(identifier: "\(prefix)-\(type)", content: content, trigger: nil))
      }
    }

    func releaseSnapshot() {
      snapshotWaiter?.resume()
      snapshotWaiter = nil
      snapshotIsHeld = false
      holdNextSnapshot = false
    }

    var operations: NotificationService.SystemOperations {
      .init(
        setBadgeCount: { self.badges.append($0) },
        deliveredRequests: {
          self.loads += 1
          if self.holdNextSnapshot {
            self.holdNextSnapshot = false
            self.snapshotIsHeld = true
            await withCheckedContinuation { self.snapshotWaiter = $0 }
            self.snapshotIsHeld = false
          }
          return self.requests
        },
        removeDeliveredNotifications: { identifiers in
          self.removed.append(contentsOf: identifiers)
          self.requests.removeAll { identifiers.contains($0.identifier) }
        }
      )
    }
  }
}
