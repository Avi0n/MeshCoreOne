import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("ContactService Remote CLI restore")
struct ContactServiceRemoteCLIRestoreTests {
  private enum RestoreMode: Sendable {
    case existing
    case inserted
  }

  private static let liveTypeRawValue: UInt8 = 0x7F
  private static let liveFlags = ContactFlags(rawValue: 0x9F)
  private static let revokedFlags = ContactFlags(rawValue: 0x8F)
  private static let incrementalSince = Date(timeIntervalSince1970: 1_700_000_000)
  private static let liveAdvertisement = Date(timeIntervalSince1970: 1_600_000_000)
  private static let liveOutPath = Data([0xA1, 0xB2])
  private static let liveName = "Live Contact"
  private static let liveLatitude = 37.5
  private static let liveLongitude = -122.25

  private func restore(
    mode: RestoreMode,
    blocked: Bool = true,
    incremental: Bool = false
  ) async throws -> (ContactService, PersistenceStore, MockMeshCoreSession, ContactDTO) {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let contact = ContactDTO.testContact(
      radioID: radioID,
      flags: ContactFlags.remoteCLIAllowed.rawValue,
      isBlocked: blocked
    )
    if mode == .existing {
      try await store.saveContact(ContactDTO.testContact(
        radioID: radioID,
        publicKey: contact.publicKey,
        flags: ContactFlags.remoteCLIAllowed.rawValue
      ))
    }
    let device = try #require(await store.fetchDevice(radioID: radioID))
    let envelope = AppBackupEnvelope.test(devices: [device], contacts: [contact])
    _ = try await AppBackupService().importBackup(envelope: envelope, into: store)
    let restored = try #require(await store.fetchContact(radioID: radioID, publicKey: contact.publicKey))
    #expect(restored.isBlocked == blocked)

    let liveContact = MeshContact(
      id: contact.publicKey.uppercaseHexString(),
      publicKey: contact.publicKey,
      type: .chat,
      typeRawValue: Self.liveTypeRawValue,
      flags: Self.liveFlags,
      outPathLength: UInt8(Self.liveOutPath.count),
      outPath: Self.liveOutPath,
      advertisedName: Self.liveName,
      lastAdvertisement: Self.liveAdvertisement,
      latitude: Self.liveLatitude,
      longitude: Self.liveLongitude,
      lastModified: Self.incrementalSince
    )
    let session = MockMeshCoreSession()
    await session.setStubbedContacts(incremental ? [] : [liveContact])
    await session.setStubbedContact(liveContact, for: contact.publicKey)
    let service = ContactService(session: session, dataStore: store, syncCoordinator: nil, cleanupCoordinator: nil)
    return (service, store, session, restored)
  }

  @Test(arguments: [(RestoreMode.existing, false), (.inserted, true)])
  private func `restored block revokes live Remote CLI during full and incremental sync`(
    mode: RestoreMode,
    incremental: Bool
  ) async throws {
    let (service, store, session, restored) = try await restore(mode: mode, incremental: incremental)

    let result = try await service.syncContacts(
      radioID: restored.radioID,
      since: incremental ? Self.incrementalSince : nil
    )

    #expect(result.contactsReceived == (incremental ? 0 : 1))
    #expect(result.lastSyncTimestamp == (incremental ? 0 : UInt32(Self.incrementalSince.timeIntervalSince1970)))
    #expect(result.isIncremental == incremental)
    let calls = await session.addContactInvocations
    #expect(calls.count == 1)
    let outgoing = try #require(calls.last?.contact)
    #expect(outgoing.publicKey == restored.publicKey)
    #expect(outgoing.flags == Self.revokedFlags)
    #expect(outgoing.typeRawValue == Self.liveTypeRawValue)
    #expect(outgoing.outPath == Self.liveOutPath)
    #expect(outgoing.advertisedName == Self.liveName)
    let synced = try #require(await store.fetchContact(id: restored.id))
    #expect(synced.isBlocked)
    #expect(!ContactService.hasRemoteCLIAllowed(flags: synced.flags))
  }

  @Test
  func `revocation failure fails sync and retries from the restored block`() async throws {
    let (service, store, session, restored) = try await restore(mode: .inserted)
    await session.setAddContactError(MeshCoreError.timeout)

    await #expect(throws: ContactServiceError.self) {
      _ = try await service.syncContacts(radioID: restored.radioID)
    }
    #expect(try await store.fetchContact(id: restored.id)?.isBlocked == true)

    await session.setAddContactError(nil)
    _ = try await service.syncContacts(radioID: restored.radioID)

    #expect(await session.addContactInvocations.count == 2)
    #expect(await session.addContactInvocations.last?.contact.flags == Self.revokedFlags)
    let synced = try #require(await store.fetchContact(id: restored.id))
    #expect(!ContactService.hasRemoteCLIAllowed(flags: synced.flags))
  }

  @Test
  func `targeted lookup failure fails sync while the restored block remains`() async throws {
    let (service, store, session, restored) = try await restore(mode: .existing, incremental: true)
    await session.setGetContactError(MeshCoreError.timeout, for: restored.publicKey)

    await #expect(throws: ContactServiceError.self) {
      _ = try await service.syncContacts(radioID: restored.radioID, since: Self.incrementalSince)
    }

    #expect(await session.getContactPublicKeys == [restored.publicKey])
    #expect(await session.addContactInvocations.isEmpty)
    #expect(try await store.fetchContact(id: restored.id)?.isBlocked == true)
  }

  @Test
  func `unblocked contact retains its live Remote CLI grant`() async throws {
    let (service, store, session, restored) = try await restore(mode: .inserted, blocked: false)

    _ = try await service.syncContacts(radioID: restored.radioID)

    #expect(await session.getContactPublicKeys.isEmpty)
    #expect(await session.addContactInvocations.isEmpty)
    let synced = try #require(await store.fetchContact(id: restored.id))
    #expect(!synced.isBlocked)
    #expect(ContactService.hasRemoteCLIAllowed(flags: synced.flags))
  }
}
