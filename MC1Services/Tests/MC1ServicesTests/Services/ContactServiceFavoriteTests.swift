@testable import MC1Services
import MeshCore
import Testing

@Suite("ContactService favorite verification")
struct ContactServiceFavoriteTests {
  private func makeService(
    isFavorite: Bool = false
  ) async throws -> (ContactService, MockPersistenceStore, MockMeshCoreSession, ContactDTO) {
    let session = MockMeshCoreSession()
    let store = MockPersistenceStore()
    let otherFlags = ContactFlags.telemetryAll.rawValue | ContactFlags.remoteCLIAllowed.rawValue
    let contact = ContactDTO.testContact(
      flags: otherFlags | (isFavorite ? ContactFlags.favorite.rawValue : 0),
      isFavorite: isFavorite
    )
    try await store.saveContact(contact)
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )
    return (service, store, session, contact)
  }

  private func radioContact(_ contact: ContactDTO, isFavorite: Bool) -> MeshContact {
    let flags = isFavorite
      ? contact.flags | ContactFlags.favorite.rawValue
      : contact.flags & ~ContactFlags.favorite.rawValue
    return ContactDTO.testContact(publicKey: contact.publicKey, flags: flags)
      .toContactFrame().toMeshContact()
  }

  @Test(arguments: [true, false])
  func `favorite persists only after matching radio readback`(requestedFavorite: Bool) async throws {
    let (service, store, session, contact) = try await makeService(isFavorite: !requestedFavorite)
    let confirmed = radioContact(contact, isFavorite: requestedFavorite)
    await session.setStubbedContact(confirmed, for: contact.publicKey)

    let updated = try await service.setContactFavorite(contact.id, isFavorite: requestedFavorite)

    #expect(await session.getContactPublicKeys == [contact.publicKey])
    #expect(await session.changeContactFlagsCalls.count == 1)
    #expect(await session.changeContactFlagsCalls.first?.flags == confirmed.flags)
    let saved = try #require(await store.contacts[contact.id])
    #expect(saved.isFavorite == requestedFavorite)
    #expect(saved.flags == confirmed.flags.rawValue)
    #expect(updated.id == contact.id)
    #expect(updated.isFavorite == requestedFavorite)
    #expect(updated.flags == confirmed.flags.rawValue)
  }

  @Test(arguments: [true, false])
  func `mismatched favorite readback leaves local state unchanged`(requestedFavorite: Bool) async throws {
    let (service, store, session, contact) = try await makeService(isFavorite: !requestedFavorite)
    await session.setStubbedContact(radioContact(contact, isFavorite: !requestedFavorite), for: contact.publicKey)

    await #expect {
      try await service.setContactFavorite(contact.id, isFavorite: requestedFavorite)
    } throws: { error in
      guard case ContactServiceError.invalidResponse = error else { return false }
      return true
    }

    let saved = try #require(await store.contacts[contact.id])
    #expect(saved.isFavorite == contact.isFavorite)
    #expect(saved.flags == contact.flags)
  }

  @Test
  func `missing radio readback leaves local favorite unchanged`() async throws {
    let (service, store, session, contact) = try await makeService()

    await #expect {
      try await service.setContactFavorite(contact.id, isFavorite: true)
    } throws: { error in
      guard case ContactServiceError.contactNotFound = error else { return false }
      return true
    }

    #expect(await session.getContactPublicKeys == [contact.publicKey])
    #expect(await store.contacts[contact.id]?.isFavorite == false)
    #expect(await store.contacts[contact.id]?.flags == contact.flags)
  }
}
