import Foundation
import Testing
import MeshCore
@testable import MC1Services

/// Covers the login auto-heal path: when the radio reports a contact missing from its table
/// (firmware notFound, 0x02) during `sendLogin`, the service pushes the local contact to the radio
/// and retries once.
@Suite("RemoteNodeService login heal")
struct RemoteNodeLoginHealTests {

    private static let publicKey = Data(repeating: 0xCC, count: 32)
    private static let directPath = Data([0x01, 0x02])

    @Test("notFound on login pushes the local contact to the radio and retries")
    func healsMissingContactThenRetries() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        // A direct-routed contact present locally but absent from the radio's table.
        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Self.publicKey,
            outPathLength: 2,
            outPath: Self.directPath
        )
        try await dataStore.saveContact(contact)

        let session = MockMeshCoreSession()
        await session.setSendLoginResults([
            .failure(MeshCoreError.deviceError(code: ProtocolError.notFound.rawValue)),
            .success(MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 5000))
        ])

        let service = RemoteNodeService(session: session, dataStore: dataStore, keychainService: KeychainService())

        _ = try await service.sendLoginHealingIfNeeded(
            publicKey: Self.publicKey,
            radioID: radioID,
            password: ""
        )

        // The radio was sent the missing contact exactly once, and login was retried.
        #expect(await session.addContactInvocations.count == 1)
        #expect(await session.sendLoginInvocations.count == 2)
        // The pushed contact is the right node, flood-routed, and the local row is reconciled to match.
        let pushed = await session.addContactInvocations.first?.contact
        #expect(pushed?.publicKey == Self.publicKey)
        #expect(pushed?.outPathLength == PacketBuilder.floodPathSentinel)
        let healed = try await dataStore.fetchContact(radioID: radioID, publicKey: Self.publicKey)
        #expect(healed?.isFloodRouted == true)
    }

    @Test("healing preserves a contact type byte not modeled by ContactType")
    func healingPreservesUnmodeledTypeByte() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        // A type byte newer firmware might use that ContactType does not model; it must reach the
        // radio verbatim rather than being coerced to .chat by the typed accessor.
        let unmodeledType: UInt8 = 0x7F
        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Self.publicKey,
            typeRawValue: unmodeledType,
            outPathLength: 2,
            outPath: Self.directPath
        )
        try await dataStore.saveContact(contact)

        let session = MockMeshCoreSession()
        await session.setSendLoginResults([
            .failure(MeshCoreError.deviceError(code: ProtocolError.notFound.rawValue)),
            .success(MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 5000))
        ])

        let service = RemoteNodeService(session: session, dataStore: dataStore, keychainService: KeychainService())

        _ = try await service.sendLoginHealingIfNeeded(
            publicKey: Self.publicKey,
            radioID: radioID,
            password: ""
        )

        let pushed = await session.addContactInvocations.first?.contact
        #expect(pushed?.typeRawValue == unmodeledType)
    }

    @Test("a non-notFound login error skips healing and is not retried")
    func nonNotFoundErrorSkipsHealing() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Self.publicKey,
            outPathLength: 2,
            outPath: Self.directPath
        )
        try await dataStore.saveContact(contact)

        let session = MockMeshCoreSession()
        // tableFull shares its code with the add-contact path; only notFound from sendLogin may heal.
        await session.setSendLoginResults([
            .failure(MeshCoreError.deviceError(code: ProtocolError.tableFull.rawValue))
        ])

        let service = RemoteNodeService(session: session, dataStore: dataStore, keychainService: KeychainService())

        let thrown = await #expect(throws: MeshCoreError.self) {
            _ = try await service.sendLoginHealingIfNeeded(
                publicKey: Self.publicKey,
                radioID: radioID,
                password: ""
            )
        }
        guard case .deviceError(let code) = thrown, code == ProtocolError.tableFull.rawValue else {
            Issue.record("expected the original tableFull device error, got \(String(describing: thrown))")
            return
        }
        #expect(await session.addContactInvocations.isEmpty)
        #expect(await session.sendLoginInvocations.count == 1)
    }

    @Test("a second notFound after re-adding the contact surfaces the device error unhealed")
    func secondNotFoundAfterHealPropagates() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Self.publicKey,
            outPathLength: 2,
            outPath: Self.directPath
        )
        try await dataStore.saveContact(contact)

        let session = MockMeshCoreSession()
        await session.setSendLoginResults([
            .failure(MeshCoreError.deviceError(code: ProtocolError.notFound.rawValue)),
            .failure(MeshCoreError.deviceError(code: ProtocolError.notFound.rawValue))
        ])

        let service = RemoteNodeService(session: session, dataStore: dataStore, keychainService: KeychainService())

        let thrown = await #expect(throws: MeshCoreError.self) {
            _ = try await service.sendLoginHealingIfNeeded(
                publicKey: Self.publicKey,
                radioID: radioID,
                password: ""
            )
        }
        guard case .deviceError(let code) = thrown, code == ProtocolError.notFound.rawValue else {
            Issue.record("expected notFound device error, got \(String(describing: thrown))")
            return
        }
        // The contact was re-added once, then the retry's second notFound surfaced without re-adding.
        #expect(await session.addContactInvocations.count == 1)
        #expect(await session.sendLoginInvocations.count == 2)
    }

    @Test("a full radio contact table surfaces radioContactsFull")
    func tableFullSurfacesActionableError() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Self.publicKey,
            outPathLength: 2,
            outPath: Self.directPath
        )
        try await dataStore.saveContact(contact)

        let session = MockMeshCoreSession()
        await session.setSendLoginResults([
            .failure(MeshCoreError.deviceError(code: ProtocolError.notFound.rawValue))
        ])
        await session.setAddContactError(MeshCoreError.deviceError(code: ProtocolError.tableFull.rawValue))

        let service = RemoteNodeService(session: session, dataStore: dataStore, keychainService: KeychainService())

        let thrown = await #expect(throws: RemoteNodeError.self) {
            _ = try await service.sendLoginHealingIfNeeded(
                publicKey: Self.publicKey,
                radioID: radioID,
                password: ""
            )
        }
        guard case .radioContactsFull = thrown else {
            Issue.record("expected radioContactsFull, got \(String(describing: thrown))")
            return
        }
        // The add was attempted once and the retry never fired, so login stops at the full table.
        #expect(await session.addContactInvocations.count == 1)
        #expect(await session.sendLoginInvocations.count == 1)
    }

    @Test("a contact absent from the local store cannot be healed")
    func missingLocalContactCannotBeHealed() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

        let session = MockMeshCoreSession()
        await session.setSendLoginResults([
            .failure(MeshCoreError.deviceError(code: ProtocolError.notFound.rawValue))
        ])

        let service = RemoteNodeService(session: session, dataStore: dataStore, keychainService: KeychainService())

        let thrown = await #expect(throws: RemoteNodeError.self) {
            _ = try await service.sendLoginHealingIfNeeded(
                publicKey: Self.publicKey,
                radioID: radioID,
                password: ""
            )
        }
        guard case .contactNotFound = thrown else {
            Issue.record("expected contactNotFound, got \(String(describing: thrown))")
            return
        }
        #expect(await session.addContactInvocations.isEmpty)
    }

    @Test("login() surfaces radioContactsFull through the continuation instead of a generic session error")
    func loginPreservesTypedHealError() async throws {
        let radioID = UUID()
        let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        let remoteSession = RemoteNodeSessionDTO.testSession(radioID: radioID, publicKey: Self.publicKey)
        try await dataStore.saveRemoteNodeSessionDTO(remoteSession)
        let contact = ContactDTO.testContact(
            radioID: radioID,
            publicKey: Self.publicKey,
            outPathLength: 2,
            outPath: Self.directPath
        )
        try await dataStore.saveContact(contact)

        // The radio is missing the contact (notFound), and re-adding it hits a full table. The error the
        // public login() resolves the continuation with must stay the typed RemoteNodeError.radioContactsFull,
        // not collapse into the generic .sessionError that the catch falls back to.
        let session = MockMeshCoreSession()
        await session.setSendLoginResults([
            .failure(MeshCoreError.deviceError(code: ProtocolError.notFound.rawValue))
        ])
        await session.setAddContactError(MeshCoreError.deviceError(code: ProtocolError.tableFull.rawValue))

        let service = RemoteNodeService(session: session, dataStore: dataStore, keychainService: KeychainService())

        let thrown = await #expect(throws: RemoteNodeError.self) {
            _ = try await service.login(sessionID: remoteSession.id, password: "")
        }
        guard case .radioContactsFull = thrown else {
            Issue.record("expected radioContactsFull to propagate unwrapped, got \(String(describing: thrown))")
            return
        }
        #expect(await session.sendLoginInvocations.count == 1)
        #expect(await session.addContactInvocations.count == 1)
    }
}
