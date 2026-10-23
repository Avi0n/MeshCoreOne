import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("ContactService remote CLI flag")
struct ContactServiceRemoteCLITests {
  private func makeService(radioID: UUID) async throws -> (ContactService, PersistenceStore, MockMeshCoreSession) {
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let session = MockMeshCoreSession()
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )
    return (service, store, session)
  }

  @Test
  func `grant and revoke preserve favorite and telemetry bits`() async throws {
    let radioID = UUID()
    let (service, store, session) = try await makeService(radioID: radioID)
    let contact = ContactDTO.testContact(
      radioID: radioID,
      flags: ContactFlags.favorite.rawValue | ContactFlags.telemetryAll.rawValue
    )
    try await store.saveContact(contact)

    try await service.setRemoteCLIAllowed(contact.id, granted: true)
    let granted = try #require(await store.fetchContact(id: contact.id))
    #expect(ContactService.hasRemoteCLIAllowed(flags: granted.flags))
    #expect(granted.flags & ContactFlags.favorite.rawValue != 0)
    #expect(ContactService.hasTelemetryPermissions(flags: granted.flags))
    #expect(await session.changeContactFlagsCalls.last?.flags == ContactFlags(rawValue: granted.flags))

    try await service.setRemoteCLIAllowed(contact.id, granted: false)
    let revoked = try #require(await store.fetchContact(id: contact.id))
    #expect(!ContactService.hasRemoteCLIAllowed(flags: revoked.flags))
    #expect(revoked.flags & ContactFlags.favorite.rawValue != 0)
    #expect(ContactService.hasTelemetryPermissions(flags: revoked.flags))
  }

  @Test
  func `backup insert of flags 0x10 leaves remote CLI disarmed`() async throws {
    let radioID = UUID()
    let (service, store, session) = try await makeService(radioID: radioID)
    let incoming = ContactDTO.testContact(radioID: radioID, flags: 0x10)
    let result = try await store.batchInsertContacts([incoming], radioIDs: [radioID])
    #expect(result.inserted == 1)
    let insertedID = try #require(result.contactIDsByKey.values.first)
    let inserted = try #require(await store.fetchContact(id: insertedID))
    #expect(!ContactService.hasRemoteCLIAllowed(flags: inserted.flags))

    let encoded = try JSONEncoder().encode(incoming)
    let decoded = try JSONDecoder().decode(ContactDTO.self, from: encoded)
    #expect(decoded.flags == 0x10)

    let confirmed = ContactDTO.testContact(publicKey: inserted.publicKey, flags: ContactFlags.favorite.rawValue)
    await session.setStubbedContact(confirmed.toContactFrame().toMeshContact(), for: inserted.publicKey)
    try await service.setContactFavorite(inserted.id, isFavorite: true)
    #expect(await session.changeContactFlagsCalls.last?.flags.contains(.remoteCLIAllowed) == false)

    try await service.setTelemetryPermissions(inserted.id, granted: true)
    #expect(await session.changeContactFlagsCalls.last?.flags.contains(.remoteCLIAllowed) == false)
  }

  @Test
  func `block after grant clears remote CLI via changeContactFlags`() async throws {
    let radioID = UUID()
    let (service, store, session) = try await makeService(radioID: radioID)
    let contact = ContactDTO.testContact(radioID: radioID)
    try await store.saveContact(contact)

    try await service.setRemoteCLIAllowed(contact.id, granted: true)
    try await service.updateContactPreferences(contactID: contact.id, isBlocked: true)

    let blocked = try #require(await store.fetchContact(id: contact.id))
    #expect(blocked.isBlocked)
    #expect(!ContactService.hasRemoteCLIAllowed(flags: blocked.flags))
    #expect(await session.changeContactFlagsCalls.last?.flags.contains(.remoteCLIAllowed) == false)
  }
}
