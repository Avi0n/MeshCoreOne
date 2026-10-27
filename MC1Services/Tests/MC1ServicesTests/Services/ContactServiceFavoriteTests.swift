import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("ContactService favorite verification")
struct ContactServiceFavoriteTests {
  private func makeService(
    isFavorite: Bool = false,
    coordinator: SyncCoordinator? = nil
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
      syncCoordinator: coordinator,
      cleanupCoordinator: nil
    )
    return (service, store, session, contact)
  }

  /// Subscribes before `body`, then finishes the stream so a missing event ends the read.
  /// Each value is the stored favourite at the moment that invalidation is consumed.
  private func storedFavoritesOnContactChange(
    coordinator: SyncCoordinator,
    store: MockPersistenceStore,
    contactID: UUID,
    during body: () async throws -> Void
  ) async throws -> [Bool] {
    let stream = coordinator.dataEvents()
    let task = Task {
      var favorites: [Bool] = []
      for await event in stream {
        guard case .contactsChanged = event else { continue }
        let stored = await store.contacts[contactID]?.isFavorite ?? false
        favorites.append(stored)
      }
      return favorites
    }
    do {
      try await body()
    } catch {
      coordinator.finishDataEvents()
      _ = await task.value
      throw error
    }
    coordinator.finishDataEvents()
    return await task.value
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

  @Test(arguments: [true, false])
  func `committed favorite publishes one contact invalidation`(requestedFavorite: Bool) async throws {
    let coordinator = SyncCoordinator()
    let (service, store, session, contact) = try await makeService(
      isFavorite: !requestedFavorite,
      coordinator: coordinator
    )
    await session.setStubbedContact(radioContact(contact, isFavorite: requestedFavorite), for: contact.publicKey)

    let observed = try await storedFavoritesOnContactChange(
      coordinator: coordinator,
      store: store,
      contactID: contact.id
    ) {
      _ = try await service.setContactFavorite(contact.id, isFavorite: requestedFavorite)
    }

    #expect(observed == [requestedFavorite])
  }

  @Test
  func `radio failure publishes no contact invalidation`() async throws {
    let coordinator = SyncCoordinator()
    let (service, store, session, contact) = try await makeService(coordinator: coordinator)
    await session.setChangeContactFlagsHold { throw MeshCoreError.timeout }

    let observed = try await storedFavoritesOnContactChange(
      coordinator: coordinator,
      store: store,
      contactID: contact.id
    ) {
      await #expect {
        try await service.setContactFavorite(contact.id, isFavorite: true)
      } throws: { error in
        guard case ContactServiceError.sessionError = error else { return false }
        return true
      }
    }

    #expect(observed.isEmpty)
    #expect(await store.contacts[contact.id]?.isFavorite == false)
  }

  @Test
  func `mismatched and missing readback publish no contact invalidation`() async throws {
    let mismatched = SyncCoordinator()
    let (mismatchService, mismatchStore, mismatchSession, mismatchContact) = try await makeService(
      coordinator: mismatched
    )
    await mismatchSession.setStubbedContact(radioContact(mismatchContact, isFavorite: false), for: mismatchContact.publicKey)
    let mismatchedEvents = try await storedFavoritesOnContactChange(
      coordinator: mismatched,
      store: mismatchStore,
      contactID: mismatchContact.id
    ) {
      await #expect {
        try await mismatchService.setContactFavorite(mismatchContact.id, isFavorite: true)
      } throws: { error in
        guard case ContactServiceError.invalidResponse = error else { return false }
        return true
      }
    }
    #expect(mismatchedEvents.isEmpty)
    #expect(await mismatchStore.contacts[mismatchContact.id]?.isFavorite == false)

    let missing = SyncCoordinator()
    let (missingService, missingStore, _, missingContact) = try await makeService(coordinator: missing)
    let missingEvents = try await storedFavoritesOnContactChange(
      coordinator: missing,
      store: missingStore,
      contactID: missingContact.id
    ) {
      await #expect {
        try await missingService.setContactFavorite(missingContact.id, isFavorite: true)
      } throws: { error in
        guard case ContactServiceError.contactNotFound = error else { return false }
        return true
      }
    }
    #expect(missingEvents.isEmpty)
    #expect(await missingStore.contacts[missingContact.id]?.isFavorite == false)
  }

  @Test
  func `save failure publishes no contact invalidation`() async throws {
    let coordinator = SyncCoordinator()
    let (service, store, session, contact) = try await makeService(coordinator: coordinator)
    await session.setStubbedContact(radioContact(contact, isFavorite: true), for: contact.publicKey)
    await store.setStubbedSaveContactError(PersistenceStoreError.contactNotFound)

    let observed = try await storedFavoritesOnContactChange(
      coordinator: coordinator,
      store: store,
      contactID: contact.id
    ) {
      await #expect {
        try await service.setContactFavorite(contact.id, isFavorite: true)
      } throws: { error in
        guard let error = error as? PersistenceStoreError, case .contactNotFound = error else { return false }
        return true
      }
    }

    #expect(observed.isEmpty)
    #expect(await store.contacts[contact.id]?.isFavorite == false)
  }

  @Test
  func `fetch failure after save still publishes the committed favorite`() async throws {
    let coordinator = SyncCoordinator()
    let (service, store, session, contact) = try await makeService(coordinator: coordinator)
    await session.setStubbedContact(radioContact(contact, isFavorite: true), for: contact.publicKey)
    // Call 1 reads the row. Call 2 is the post-save fetch.
    await store.failFetchContactID(onCall: 2)

    let observed = try await storedFavoritesOnContactChange(
      coordinator: coordinator,
      store: store,
      contactID: contact.id
    ) {
      await #expect {
        try await service.setContactFavorite(contact.id, isFavorite: true)
      } throws: { error in
        guard let error = error as? PersistenceStoreError, case .contactNotFound = error else { return false }
        return true
      }
    }

    #expect(observed == [true])
    #expect(await store.contacts[contact.id]?.isFavorite == true)
  }
}
