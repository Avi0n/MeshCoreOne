import Foundation
@testable import MC1Services
@testable import MeshCore
import os
import Testing

@Suite("Contact snapshot writes", .timeLimit(.minutes(1)))
struct ContactSnapshotWriteTests {
  private func publicKey(_ byte: UInt8) -> Data {
    Data(repeating: byte, count: ProtocolLimits.publicKeySize)
  }

  private func meshContact(
    key: Data,
    name: String,
    typeRawValue: UInt8 = ContactType.chat.rawValue,
    flags: ContactFlags = [],
    outPathLength: UInt8 = 1,
    outPath: Data = Data([0x22])
  ) -> MeshContact {
    MeshContact(
      id: key.uppercaseHexString(),
      publicKey: key,
      type: ContactType(rawValue: typeRawValue) ?? .chat,
      typeRawValue: typeRawValue,
      flags: flags,
      outPathLength: outPathLength,
      outPath: outPath,
      advertisedName: name,
      lastAdvertisement: Date(timeIntervalSince1970: 0),
      latitude: 0,
      longitude: 0,
      lastModified: Date(timeIntervalSince1970: 0)
    )
  }

  private func makeFixture(
    keyByte: UInt8 = 0xAA,
    name: String = "Phone Name",
    flags: UInt8 = 0,
    outPathLength: UInt8 = 1,
    outPath: Data = Data([0x11]),
    nickname: String? = "Nick",
    isBlocked: Bool = false,
    isFavorite: Bool = false,
    unreadCount: Int = 2,
    unreadMentionCount: Int = 1,
    avatarImageData: Data? = Data([0xAB]),
    cleanup: (any ContactCleanupHandling)? = nil
  ) async throws -> SnapshotFixture {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let key = publicKey(keyByte)
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: key,
      name: name,
      flags: flags,
      outPathLength: outPathLength,
      outPath: outPath,
      nickname: nickname,
      isBlocked: isBlocked,
      isFavorite: isFavorite,
      unreadCount: unreadCount,
      unreadMentionCount: unreadMentionCount,
      avatarImageData: avatarImageData
    )
    try await store.saveContact(contact)
    let session = MockMeshCoreSession()
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: cleanup
    )
    return SnapshotFixture(
      radioID: radioID,
      contact: contact,
      store: store,
      session: session,
      service: service
    )
  }

  private func waitUntilWaiters(_ service: ContactService, equal expected: Int) async {
    while await service.contactOperationWaiterCountForTesting() != expected {
      await Task.yield()
    }
  }

  private func bumpDuringHold(_ fixture: SnapshotFixture, gate: SuspendGate) async throws -> Date {
    await gate.waitForEntry()
    try await fixture.store.incrementUnreadCount(contactID: fixture.contact.id)
    try await fixture.store.incrementUnreadMentionCount(contactID: fixture.contact.id)
    let date = Date(timeIntervalSince1970: 1_700_000_000)
    try await fixture.store.updateContactLastMessage(contactID: fixture.contact.id, date: date)
    _ = try await fixture.store.updateContactRoute(
      radioID: fixture.radioID,
      publicKey: fixture.contact.publicKey,
      outPathLength: 2,
      outPath: Data([0x44, 0x55])
    )
    await gate.release()
    return date
  }

  @Test(arguments: [RadioFlagPeer.favorite, .telemetry, .remoteCLI])
  func `radio flag writes keep newer phone fields and the radio path`(peer: RadioFlagPeer) async throws {
    let fixture = try await makeFixture(flags: ContactFlags.favorite.rawValue)
    let radio = meshContact(
      key: fixture.contact.publicKey,
      name: "Radio Name",
      typeRawValue: 0x7F,
      flags: [.favorite],
      outPathLength: 3,
      outPath: Data([0x22, 0x33, 0x44])
    )
    await fixture.session.seedRadioContact(radio)
    let gate = SuspendGate()
    await fixture.session.setChangeContactFlagsHold { @Sendable in
      await gate.enter()
      await gate.waitForRelease()
    }

    let task = Task {
      try await peer.apply(fixture)
    }
    let date = try await bumpDuringHold(fixture, gate: gate)
    try await task.value

    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.name == "Phone Name")
    #expect(stored.nickname == "Nick")
    #expect(stored.typeRawValue == ContactType.chat.rawValue)
    #expect(stored.unreadCount == 3)
    #expect(stored.unreadMentionCount == 2)
    #expect(stored.lastMessageDate == date)
    #expect(stored.outPath == Data([0x44, 0x55]))
    #expect(stored.outPathLength == 2)
    #expect(stored.avatarImageData == Data([0xAB]))
    #expect(peer.satisfied(by: stored))

    let call = try #require(await fixture.session.changeContactFlagsCalls.first)
    #expect(call.contact.advertisedName == "Radio Name")
    #expect(call.contact.typeRawValue == 0x7F)
    #expect(call.contact.outPath == Data([0x22, 0x33, 0x44]))
    #expect(call.contact.outPathLength == 3)
    #expect((call.flags.rawValue & peer.mask) == peer.mask)
    #expect(call.flags.contains(.favorite))
  }

  @Test
  func `local preference avatar and ocv writes leave unrelated fields`(
  ) async throws {
    let fixture = try await makeFixture()
    let date = Date(timeIntervalSince1970: 1_700_000_100)
    try await fixture.store.updateContactLastMessage(contactID: fixture.contact.id, date: date)

    try await fixture.service.updateContactPreferences(
      contactID: fixture.contact.id,
      nickname: "  Field Ops  "
    )
    try await fixture.service.updateContactOCVSettings(
      contactID: fixture.contact.id,
      preset: OCVPreset.custom.rawValue,
      customArray: "1,2,3"
    )
    try await fixture.service.updateContactAvatar(
      contactID: fixture.contact.id,
      imageData: Data([0x01])
    )

    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.nickname == "Field Ops")
    #expect(stored.ocvPreset == OCVPreset.custom.rawValue)
    #expect(stored.customOCVArrayString == "1,2,3")
    #expect(stored.avatarImageData == Data([0x01]))
    #expect(stored.unreadCount == 2)
    #expect(stored.unreadMentionCount == 1)
    #expect(stored.lastMessageDate == date)
    #expect(stored.outPath == Data([0x11]))
    #expect(stored.name == "Phone Name")

    try await fixture.service.updateContactPreferences(contactID: fixture.contact.id, nickname: nil)
    try await fixture.service.updateContactOCVSettings(
      contactID: fixture.contact.id,
      preset: OCVPreset.custom.rawValue,
      customArray: nil
    )
    try await fixture.service.updateContactAvatar(contactID: fixture.contact.id, imageData: nil)
    let cleared = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(cleared.nickname == "Field Ops")
    #expect(cleared.customOCVArrayString == nil)
    #expect(cleared.avatarImageData == nil)
    #expect(cleared.unreadCount == 2)

    try await fixture.service.updateContactPreferences(contactID: fixture.contact.id, nickname: "   ")
    let blank = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(blank.nickname == nil)
    #expect(blank.outPath == Data([0x11]))
  }

  @Test
  func `favourite and telemetry both orders keep both bits`() async throws {
    try await assertFlagOrder(first: .favorite, second: .telemetry)
    try await assertFlagOrder(first: .telemetry, second: .favorite)
  }

  @Test
  func `a flag write does not change another radio`() async throws {
    let fixture = try await makeFixture()
    let otherRadio = UUID()
    let other = ContactDTO.testContact(
      radioID: otherRadio,
      publicKey: fixture.contact.publicKey,
      name: "Other Radio",
      unreadCount: 9,
      unreadMentionCount: 4
    )
    try await fixture.store.saveContact(other)
    await fixture.session.seedRadioContact(
      meshContact(key: fixture.contact.publicKey, name: "Radio Name", flags: [])
    )

    _ = try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)

    let untouched = try #require(try await fixture.store.fetchContact(id: other.id))
    #expect(untouched.name == "Other Radio")
    #expect(untouched.unreadCount == 9)
    #expect(untouched.unreadMentionCount == 4)
    #expect(!untouched.isFavorite)
  }

  @Test
  func `missing radio contact does not write flags or resurrect a row`() async throws {
    let fixture = try await makeFixture()
    await #expect {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    } throws: { error in
      guard case ContactServiceError.contactNotFound = error else { return false }
      return true
    }
    await #expect {
      try await fixture.service.setTelemetryPermissions(fixture.contact.id, granted: true)
    } throws: { error in
      guard case ContactServiceError.contactNotFound = error else { return false }
      return true
    }
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(!stored.isFavorite)
    #expect(stored.flags == 0)
    #expect(await fixture.session.changeContactFlagsCalls.isEmpty)
    #expect(await fixture.session.addContactInvocations.isEmpty)
  }

  @Test
  func `blocking a missing radio contact does not add it back`() async throws {
    let fixture = try await makeFixture(flags: ContactFlags.remoteCLIAllowed.rawValue)
    try await fixture.service.updateContactPreferences(
      contactID: fixture.contact.id,
      isBlocked: true
    )
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.isBlocked)
    #expect(stored.unreadCount == 0)
    #expect(stored.unreadMentionCount == 1)
    #expect((stored.flags & ContactFlags.remoteCLIAllowed.rawValue) == 0)
    #expect(await fixture.session.changeContactFlagsCalls.isEmpty)
    #expect(await fixture.session.addContactInvocations.isEmpty)
  }

  @Test
  func `favourite held across a store delete does not resurrect the row`() async throws {
    let fixture = try await makeFixture()
    await fixture.session.seedRadioContact(
      meshContact(key: fixture.contact.publicKey, name: "Radio Name")
    )
    let gate = SuspendGate()
    await fixture.session.setChangeContactFlagsHold { @Sendable in
      await gate.enter()
      await gate.waitForRelease()
    }

    let task = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    await gate.waitForEntry()
    try await fixture.store.deleteContact(id: fixture.contact.id)
    await gate.release()

    await #expect {
      try await task.value
    } throws: { error in
      guard case ContactServiceError.contactNotFound = error else { return false }
      return true
    }
    #expect(try await fixture.store.fetchContact(id: fixture.contact.id) == nil)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `remove queued behind favourite deletes the committed row`() async throws {
    let fixture = try await seededFavoriteFixture()
    let gate = SuspendGate()
    await fixture.session.setChangeContactFlagsHold { @Sendable in
      await gate.enter()
      await gate.waitForRelease()
    }
    let favorite = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    await gate.waitForEntry()
    let removal = Task {
      try await fixture.service.removeContact(
        radioID: fixture.radioID,
        publicKey: fixture.contact.publicKey
      )
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    await gate.release()

    _ = try await favorite.value
    try await removal.value
    #expect(try await fixture.store.fetchContact(id: fixture.contact.id) == nil)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `favourite queued behind remove does not resurrect the row`() async throws {
    let fixture = try await seededFavoriteFixture()
    let gate = SuspendGate()
    await fixture.session.setRemoveContactHold { @Sendable in
      await gate.enter()
      await gate.waitForRelease()
    }
    let removal = Task {
      try await fixture.service.removeContact(
        radioID: fixture.radioID,
        publicKey: fixture.contact.publicKey
      )
    }
    await gate.waitForEntry()
    let favorite = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    await gate.release()

    try await removal.value
    await #expect {
      try await favorite.value
    } throws: { error in
      guard case ContactServiceError.contactNotFound = error else { return false }
      return true
    }
    #expect(try await fixture.store.fetchContact(id: fixture.contact.id) == nil)
  }

  @Test
  func `removeLocal queued behind favourite deletes the row`() async throws {
    let fixture = try await seededFavoriteFixture()
    let gate = SuspendGate()
    await fixture.session.setChangeContactFlagsHold { @Sendable in
      await gate.enter()
      await gate.waitForRelease()
    }
    let favorite = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    await gate.waitForEntry()
    let removal = Task {
      try await fixture.service.removeLocalContact(
        contactID: fixture.contact.id,
        publicKey: fixture.contact.publicKey
      )
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    await gate.release()

    _ = try await favorite.value
    try await removal.value
    #expect(try await fixture.store.fetchContact(id: fixture.contact.id) == nil)
  }

  @Test
  func `favourite queued behind removeLocal does not resurrect the row`() async throws {
    let fixture = try await seededFavoriteFixture()
    await fixture.session.holdNextGetContacts()
    await fixture.session.setStubbedContacts([
      meshContact(key: fixture.contact.publicKey, name: "Radio Name")
    ])
    let sync = Task {
      try await fixture.service.syncContacts(radioID: fixture.radioID)
    }
    await fixture.session.waitForGetContactsStart()
    let removal = Task {
      try await fixture.service.removeLocalContact(
        contactID: fixture.contact.id,
        publicKey: fixture.contact.publicKey
      )
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    let favorite = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    await waitUntilWaiters(fixture.service, equal: 2)
    await fixture.session.releaseGetContacts()

    _ = try await sync.value
    try await removal.value
    await #expect {
      try await favorite.value
    } throws: { error in
      guard case ContactServiceError.contactNotFound = error else { return false }
      return true
    }
    #expect(try await fixture.store.fetchContact(id: fixture.contact.id) == nil)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `v-contact removal does not issue a radio remove or delete the row`() async throws {
    let selfKey = Data(repeating: 0x01, count: ProtocolLimits.publicKeySize)
    let vKey = try #require(VContactIdentity.publicKey(forSelfPublicKey: selfKey))
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let contact = ContactDTO.testContact(radioID: radioID, publicKey: vKey, name: "V")
    try await store.saveContact(contact)
    let session = MockMeshCoreSession()
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    try await service.removeContact(radioID: radioID, publicKey: vKey)

    #expect(await session.removeContactPublicKeys.isEmpty)
    #expect(try await store.fetchContact(id: contact.id) != nil)
  }

  @Test
  func `block then unblock keeps the newer unblock and mention count`() async throws {
    let cleanup = RecordingCleanup()
    let fixture = try await makeFixture(
      flags: ContactFlags.remoteCLIAllowed.rawValue,
      cleanup: cleanup
    )
    await fixture.session.seedRadioContact(
      meshContact(
        key: fixture.contact.publicKey,
        name: "Radio Name",
        flags: [.remoteCLIAllowed]
      )
    )
    let gate = SuspendGate()
    await fixture.session.setChangeContactFlagsHold { @Sendable in
      await gate.enter()
      await gate.waitForRelease()
    }

    let block = Task {
      try await fixture.service.updateContactPreferences(
        contactID: fixture.contact.id,
        isBlocked: true
      )
    }
    await gate.waitForEntry()
    try await fixture.store.incrementUnreadMentionCount(contactID: fixture.contact.id)
    let unblock = Task {
      try await fixture.service.updateContactPreferences(
        contactID: fixture.contact.id,
        isBlocked: false
      )
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    await gate.release()
    try await block.value
    try await unblock.value

    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(!stored.isBlocked)
    #expect(stored.unreadCount == 0)
    #expect(stored.unreadMentionCount == 2)
    #expect(cleanup.recordedReasons() == ["blocked", "unblocked"])
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `sync then setPath and add finish without deadlock`() async throws {
    let fixture = try await seededFavoriteFixture()
    await fixture.session.holdNextGetContacts()
    await fixture.session.setStubbedContacts([
      meshContact(
        key: fixture.contact.publicKey,
        name: "Radio Name",
        typeRawValue: 0x7F,
        outPathLength: 3,
        outPath: Data([0x22, 0x33, 0x44])
      )
    ])
    let sync = Task {
      try await fixture.service.syncContacts(radioID: fixture.radioID)
    }
    await fixture.session.waitForGetContactsStart()
    let path = Task {
      try await fixture.service.setPath(
        radioID: fixture.radioID,
        publicKey: fixture.contact.publicKey,
        path: Data([0x66]),
        pathLength: 1
      )
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    await fixture.session.releaseGetContacts()
    _ = try await sync.value
    try await path.value

    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.outPath == Data([0x66]))
    #expect(stored.outPathLength == 1)
    #expect(stored.unreadCount == 2)
    let added = try #require(await fixture.session.addContactInvocations.last?.contact)
    #expect(added.typeRawValue == ContactType.chat.rawValue)
    #expect(added.outPath == Data([0x66]))
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)

    await fixture.session.holdNextGetContacts()
    let secondSync = Task {
      try await fixture.service.syncContacts(radioID: fixture.radioID)
    }
    await fixture.session.waitForGetContactsStart()
    let add = Task {
      try await fixture.service.addOrUpdateContact(
        radioID: fixture.radioID,
        contact: stored.toContactFrame()
      )
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    await fixture.session.releaseGetContacts()
    _ = try await secondSync.value
    try await add.value
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `setPath writes the caller path onto the fresh radio contact`() async throws {
    let fixture = try await makeFixture(unreadCount: 4)
    await fixture.session.seedRadioContact(
      meshContact(
        key: fixture.contact.publicKey,
        name: "Radio Name",
        typeRawValue: 0x7F,
        flags: [.telemetryAll],
        outPathLength: 3,
        outPath: Data([0x22, 0x33, 0x44])
      )
    )

    try await fixture.service.setPath(
      radioID: fixture.radioID,
      publicKey: fixture.contact.publicKey,
      path: Data([0x66, 0x77]),
      pathLength: 2
    )

    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.name == "Phone Name")
    #expect(stored.outPath == Data([0x66, 0x77]))
    #expect(stored.outPathLength == 2)
    #expect(stored.unreadCount == 4)
    #expect(stored.typeRawValue == ContactType.chat.rawValue)
    let added = try #require(await fixture.session.addContactInvocations.first?.contact)
    #expect(added.advertisedName == "Radio Name")
    #expect(added.typeRawValue == 0x7F)
    #expect(added.flags.contains(.telemetryAll))
    #expect(added.outPath == Data([0x66, 0x77]))
  }

  @Test
  func `a cancelled waiter does not write after the holder finishes`() async throws {
    let fixture = try await seededFavoriteFixture()
    await fixture.session.holdNextGetContacts()
    await fixture.session.setStubbedContacts([
      meshContact(key: fixture.contact.publicKey, name: "Radio Name")
    ])
    let sync = Task {
      try await fixture.service.syncContacts(radioID: fixture.radioID)
    }
    await fixture.session.waitForGetContactsStart()
    let favorite = Task {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    favorite.cancel()
    await fixture.session.releaseGetContacts()
    _ = try await sync.value
    await #expect(throws: CancellationError.self) {
      try await favorite.value
    }
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(!stored.isFavorite)
    #expect(await fixture.session.changeContactFlagsCalls.isEmpty)
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `a failed flag write releases the gate for the next mutation`() async throws {
    let fixture = try await seededFavoriteFixture()
    await fixture.session.setChangeContactFlagsHold { throw MeshCoreError.timeout }
    await #expect(throws: ContactServiceError.self) {
      try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    }
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)

    await fixture.session.setChangeContactFlagsHold(nil)
    _ = try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.isFavorite)
    #expect(stored.outPath == Data([0x11]))
  }

  @Test
  func `region queries use the fresh radio contact and release the gate`() async throws {
    let fixture = try await makeFixture(name: "Phone Name")
    let radio = meshContact(
      key: fixture.contact.publicKey,
      name: "Radio Name",
      typeRawValue: 0x7F,
      outPathLength: 3,
      outPath: Data([0x22, 0x33, 0x44])
    )
    await fixture.session.seedRadioContact(radio)
    let proposed = meshContact(key: fixture.contact.publicKey, name: "Stale Target")

    let names = try await fixture.service.ownedRegionQuery(
      target: proposed,
      radioID: fixture.radioID
    ) { contact in
      [contact.advertisedName]
    }
    #expect(names == ["Radio Name"])
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.name == "Phone Name")
    #expect(stored.outPath == Data([0x11]))

    await fixture.session.holdNextGetContact(for: fixture.contact.publicKey)
    let first = Task {
      try await fixture.service.ownedRegionQuery(
        target: proposed,
        radioID: fixture.radioID
      ) { _ in
        ["first"]
      }
    }
    while await !fixture.session.isGetContactHeld(for: fixture.contact.publicKey) {
      await Task.yield()
    }
    let second = Task {
      try await fixture.service.ownedRegionQuery(
        target: proposed,
        radioID: fixture.radioID
      ) { _ in
        ["second"]
      }
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    await fixture.session.releaseGetContact(for: fixture.contact.publicKey)
    #expect(try await first.value == ["first"])
    #expect(try await second.value == ["second"])
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }

  @Test
  func `anonymous region targets are not persisted`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let session = MockMeshCoreSession()
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )
    let target = meshContact(key: publicKey(0xCD), name: "Anonymous")
    let names = try await service.ownedRegionQuery(target: target, radioID: radioID) { contact in
      [contact.advertisedName]
    }
    #expect(names == ["Anonymous"])
    #expect(try await store.fetchContact(radioID: radioID, publicKey: target.publicKey) == nil)
  }

  @Test
  func `a stored contact missing from the radio is not queried or written back`() async throws {
    let fixture = try await makeFixture()
    let proposed = meshContact(key: fixture.contact.publicKey, name: "Stale Target")
    let queried = OSAllocatedUnfairLock(initialState: false)
    await #expect {
      _ = try await fixture.service.ownedRegionQuery(
        target: proposed,
        radioID: fixture.radioID
      ) { _ in
        queried.withLock { $0 = true }
        return ["should-not-run"]
      }
    } throws: { error in
      guard case ContactServiceError.contactNotFound = error else { return false }
      return true
    }
    #expect(queried.withLock { $0 } == false)
    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(stored.outPath == Data([0x11]))
    #expect(stored.name == "Phone Name")
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)

    try await fixture.service.updateContactPreferences(
      contactID: fixture.contact.id,
      nickname: "After Failure"
    )
    let later = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(later.nickname == "After Failure")
    #expect(later.outPath == Data([0x11]))
  }

  @Test
  func `insert if absent does not replace an existing row and explicit save does`() async throws {
    let fixture = try await makeFixture(name: "Original", unreadCount: 5)
    let sameKey = ContactDTO.testContact(
      radioID: fixture.radioID,
      publicKey: fixture.contact.publicKey,
      name: "Replacement",
      outPath: Data([0x99]),
      unreadCount: 0
    )
    let inserted = try await fixture.store.insertContactIfAbsent(
      radioID: fixture.radioID,
      from: sameKey.toContactFrame()
    )
    #expect(!inserted.isNew)
    #expect(inserted.id == fixture.contact.id)
    let kept = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(kept.name == "Original")
    #expect(kept.unreadCount == 5)
    #expect(kept.outPath == Data([0x11]))

    _ = try await fixture.store.saveContact(radioID: fixture.radioID, from: sameKey.toContactFrame())
    let replaced = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(replaced.name == "Replacement")
    #expect(replaced.outPath == Data([0x99]))
    #expect(replaced.unreadCount == 5)
  }

  private func seededFavoriteFixture() async throws -> SnapshotFixture {
    let fixture = try await makeFixture()
    await fixture.session.seedRadioContact(
      meshContact(key: fixture.contact.publicKey, name: "Radio Name")
    )
    return fixture
  }

  private func assertFlagOrder(first: RadioFlagPeer, second: RadioFlagPeer) async throws {
    let fixture = try await makeFixture(flags: 0)
    await fixture.session.seedRadioContact(
      meshContact(key: fixture.contact.publicKey, name: "Radio Name", flags: [])
    )
    let gate = SuspendGate()
    await fixture.session.setChangeContactFlagsHold { @Sendable in
      await gate.enter()
      await gate.waitForRelease()
    }
    let firstTask = Task {
      try await first.apply(fixture)
    }
    await gate.waitForEntry()
    try await fixture.store.incrementUnreadMentionCount(contactID: fixture.contact.id)
    await fixture.session.setChangeContactFlagsHold(nil)
    let secondTask = Task {
      try await second.apply(fixture)
    }
    await waitUntilWaiters(fixture.service, equal: 1)
    await gate.release()
    try await firstTask.value
    try await secondTask.value

    let stored = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(first.satisfied(by: stored))
    #expect(second.satisfied(by: stored))
    #expect(stored.unreadMentionCount == 2)
    #expect(stored.outPath == Data([0x11]))
    #expect(await fixture.service.contactOperationWaiterCountForTesting() == 0)
  }
}

enum RadioFlagPeer: Sendable {
  case favorite
  case telemetry
  case remoteCLI

  var mask: UInt8 {
    switch self {
    case .favorite:
      ContactFlags.favorite.rawValue
    case .telemetry:
      ContactFlags.telemetryAll.rawValue
    case .remoteCLI:
      ContactFlags.remoteCLIAllowed.rawValue
    }
  }

  func apply(_ fixture: SnapshotFixture) async throws {
    switch self {
    case .favorite:
      _ = try await fixture.service.setContactFavorite(fixture.contact.id, isFavorite: true)
    case .telemetry:
      try await fixture.service.setTelemetryPermissions(fixture.contact.id, granted: true)
    case .remoteCLI:
      try await fixture.service.setRemoteCLIAllowed(fixture.contact.id, granted: true)
    }
  }

  func satisfied(by contact: ContactDTO) -> Bool {
    switch self {
    case .favorite:
      contact.isFavorite && (contact.flags & mask) == mask
    case .telemetry, .remoteCLI:
      (contact.flags & mask) == mask
    }
  }
}

struct SnapshotFixture {
  let radioID: UUID
  let contact: ContactDTO
  let store: PersistenceStore
  let session: MockMeshCoreSession
  let service: ContactService
}

private final class RecordingCleanup: ContactCleanupHandling, Sendable {
  private let reasons = OSAllocatedUnfairLock(initialState: [String]())

  func handleCleanup(contactID: UUID, reason: ContactCleanupReason, publicKey: Data) async {
    let label = switch reason {
    case .blocked:
      "blocked"
    case .unblocked:
      "unblocked"
    case .deleted:
      "deleted"
    }
    reasons.withLock { $0.append(label) }
  }

  func handleDeletion(contactID: UUID, publicKey: Data) async {}

  func flushDeletionUpdates(contactIDs: Set<UUID>) async {}

  func recordedReasons() -> [String] {
    reasons.withLock { $0 }
  }
}

private actor SuspendGate {
  private var hasEntered = false
  private var entryWaiters: [CheckedContinuation<Void, Never>] = []
  private var releaseWaiter: CheckedContinuation<Void, Never>?
  private var isReleased = false

  func enter() {
    hasEntered = true
    let waiters = entryWaiters
    entryWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }

  func waitForEntry() async {
    if hasEntered { return }
    await withCheckedContinuation { entryWaiters.append($0) }
  }

  func waitForRelease() async {
    if isReleased { return }
    await withCheckedContinuation { releaseWaiter = $0 }
  }

  func release() {
    isReleased = true
    releaseWaiter?.resume()
    releaseWaiter = nil
  }
}
