import CryptoKit
import Foundation
import Testing
@testable import MC1
@testable import MC1Services

/// The App Intents entity ids are persisted by the framework inside user-saved
/// shortcuts, so they must be stable across backup export/import (which re-mints
/// the row UUID and the firmware slot index) and must never embed a channel's
/// raw secret. These tests pin the id derivation: contact id from the public
/// key, channel id from a non-reversible secret digest, and a malformed saved id
/// failing safe to nil rather than resolving against the wrong radio.
@MainActor
struct EntityIdentityTests {

    private static let radioID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    private static let otherRadioID = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!

    private static func makeContact(
        rowID: UUID = UUID(),
        radioID: UUID = radioID,
        publicKey: Data,
        name: String = "Alice"
    ) -> ContactDTO {
        ContactDTO(
            id: rowID,
            radioID: radioID,
            publicKey: publicKey,
            name: name,
            typeRawValue: 0x01,
            flags: 0,
            outPathLength: 0,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 0,
            longitude: 0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0,
            unreadMentionCount: 0,
            ocvPreset: nil,
            customOCVArrayString: nil
        )
    }

    private static func makeChannel(
        rowID: UUID = UUID(),
        radioID: UUID = radioID,
        index: UInt8 = 0,
        name: String = "Ops",
        secret: Data
    ) -> ChannelDTO {
        ChannelDTO(
            id: rowID,
            radioID: radioID,
            index: index,
            name: name,
            secret: secret,
            isEnabled: true,
            lastMessageDate: nil,
            unreadCount: 0,
            unreadMentionCount: 0,
            notificationLevel: .all,
            isFavorite: false,
            floodScope: .inherit
        )
    }

    // MARK: - Composite id round-trip

    @Test func compositeIDRoundTrips() {
        let keyHex = Data(repeating: 0xAB, count: 32).hexString
        let id = formatCompositeID(radioID: Self.radioID, kind: .contact, keyHex: keyHex)

        let parsed = parseCompositeID(id)
        #expect(parsed?.radioID == Self.radioID)
        #expect(parsed?.kind == .contact)
        #expect(parsed?.keyHex == keyHex)
    }

    @Test(arguments: [
        "",                                          // empty
        "not-a-uuid/contact/abc",                    // bad radio scope
        "11111111-1111-1111-1111-111111111111",      // missing kind + key
        "11111111-1111-1111-1111-111111111111/contact", // missing key
        "11111111-1111-1111-1111-111111111111/contact/", // empty key
        "11111111-1111-1111-1111-111111111111/bogus/abc" // unknown kind
    ])
    func malformedCompositeIDFailsSafe(_ id: String) {
        #expect(parseCompositeID(id) == nil)
    }

    // MARK: - Contact identity

    @Test func contactIDDerivesFromPublicKeyNotRowID() {
        let publicKey = Data(repeating: 0xCD, count: 32)
        let first = MessageTargetEntity(dto: Self.makeContact(rowID: UUID(), publicKey: publicKey))
        let second = MessageTargetEntity(dto: Self.makeContact(rowID: UUID(), publicKey: publicKey))

        // Same radio + public key must yield the same id even though the volatile
        // row UUID differs (as it would after a backup export/import).
        #expect(first.id == second.id)
        #expect(first.id == formatCompositeID(radioID: Self.radioID, kind: .contact, keyHex: publicKey.hexString))
        #expect(first.kind == .contact)
    }

    @Test func contactIDIsRadioScoped() {
        let publicKey = Data(repeating: 0xCD, count: 32)
        let here = MessageTargetEntity(dto: Self.makeContact(radioID: Self.radioID, publicKey: publicKey))
        let elsewhere = MessageTargetEntity(dto: Self.makeContact(radioID: Self.otherRadioID, publicKey: publicKey))

        #expect(here.id != elsewhere.id)
    }

    // MARK: - Channel identity

    @Test func channelIDDerivesFromSecretDigestNotRawSecretOrIndexOrRowID() {
        let secret = Data(repeating: 0x5A, count: 16)
        let first = MessageTargetEntity(dto: Self.makeChannel(rowID: UUID(), index: 0, secret: secret))
        let second = MessageTargetEntity(dto: Self.makeChannel(rowID: UUID(), index: 7, secret: secret))

        // Stable across a relocated slot and a re-minted row id.
        #expect(first.id == second.id)
        #expect(first.kind == .channel)

        // The raw secret must never appear in the id (it is the live encryption key).
        let secretHex = secret.hexString
        #expect(!first.id.contains(secretHex))

        // The key portion is the domain-separated digest, not the secret.
        let parsed = parseCompositeID(first.id)
        let expectedDigest = channelSecretDigestHex(radioID: Self.radioID, secret: secret)
        #expect(parsed?.kind == .channel)
        #expect(parsed?.keyHex == expectedDigest)
        #expect(parsed?.keyHex != secretHex)
    }

    // MARK: - Contact / channel ids never collide across kinds

    @Test func contactAndChannelIDsAreDistinctEvenWhenKeyMatches() {
        // A contact public key and a channel digest are different lengths, but the
        // kind segment guarantees the two id spaces never overlap regardless.
        let contact = MessageTargetEntity(dto: Self.makeContact(publicKey: Data(repeating: 0x11, count: 32)))
        let channel = MessageTargetEntity(dto: Self.makeChannel(secret: Data(repeating: 0x11, count: 16)))
        #expect(contact.id != channel.id)
        #expect(parseCompositeID(contact.id)?.kind == .contact)
        #expect(parseCompositeID(channel.id)?.kind == .channel)
    }

    @Test func channelDigestIsRadioScoped() {
        let secret = Data(repeating: 0x5A, count: 16)
        let here = channelSecretDigestHex(radioID: Self.radioID, secret: secret)
        let elsewhere = channelSecretDigestHex(radioID: Self.otherRadioID, secret: secret)

        #expect(here != elsewhere)
    }

    @Test func distinctChannelSecretsDoNotCollide() {
        // Seed a radio's worth of channels with distinct secrets and assert every
        // entity id is unique, so a digest never mis-routes a send to the wrong
        // channel.
        let channels = (0..<8).map { i in
            Self.makeChannel(index: UInt8(i), secret: Data(repeating: UInt8(i), count: 16))
        }
        let ids = channels.map { MessageTargetEntity(dto: $0).id }
        #expect(Set(ids).count == ids.count)
    }

    @Test func channelDigestLengthIsFixedWidth() {
        let secret = Data(repeating: 0x5A, count: 16)
        let digestHex = channelSecretDigestHex(radioID: Self.radioID, secret: secret)
        // 16 bytes rendered as lowercase hex is 32 characters.
        #expect(digestHex.count == 32)
    }

    // MARK: - Persisted kind-segment string format

    @Test func kindRawValuesArePinnedStrings() {
        // The raw value is the literal segment written into framework-persisted
        // saved-shortcut ids; assert it independently of the enum symbol so a
        // future case rename that changed the on-disk string is caught here.
        #expect(MessageTargetKind.contact.rawValue == "contact")
        #expect(MessageTargetKind.channel.rawValue == "channel")
    }

    @Test func compositeIDEmbedsTheLiteralKindSegment() {
        let contactID = formatCompositeID(radioID: Self.radioID, kind: .contact, keyHex: "ab")
        let channelID = formatCompositeID(radioID: Self.radioID, kind: .channel, keyHex: "ab")

        #expect(contactID.contains("/contact/"))
        #expect(channelID.contains("/channel/"))
    }
}

/// Exercises the radio-scoped fetch and the digest disambiguation that
/// `MessageTargetQuery` delegates to, against a standalone in-memory
/// `PersistenceStore` seeded with two radios. The live queries layer an
/// `IntentBridge`/`AppState` over this same logic, so pinning it here proves the
/// scoping and fail-safe resolution without standing up a connection.
@MainActor
struct EntityQueryScopingTests {

    private static let radioA = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
    private static let radioB = UUID(uuidString: "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")!

    private func makeStore() throws -> PersistenceStore {
        let container = try PersistenceStore.createContainer(inMemory: true)
        return PersistenceStore(modelContainer: container)
    }

    private func makeContact(radioID: UUID, publicKey: Data, name: String, type: ContactType = .chat) -> ContactDTO {
        ContactDTO(
            id: UUID(), radioID: radioID, publicKey: publicKey, name: name,
            typeRawValue: type.rawValue, flags: 0, outPathLength: 0, outPath: Data(),
            lastAdvertTimestamp: 0, latitude: 0, longitude: 0, lastModified: 0,
            nickname: nil, isBlocked: false, isMuted: false, isFavorite: false,
            lastMessageDate: nil, unreadCount: 0
        )
    }

    private func makeChannel(radioID: UUID, index: UInt8, name: String, secret: Data) -> ChannelDTO {
        ChannelDTO(
            id: UUID(), radioID: radioID, index: index, name: name, secret: secret,
            isEnabled: true, lastMessageDate: nil, unreadCount: 0
        )
    }

    @Test func fetchReturnsOnlyScopedRadioRows() async throws {
        let store = try makeStore()
        try await store.saveContact(makeContact(radioID: Self.radioA, publicKey: Data(repeating: 0xA1, count: 32), name: "A-contact"))
        try await store.saveContact(makeContact(radioID: Self.radioB, publicKey: Data(repeating: 0xB1, count: 32), name: "B-contact"))
        try await store.saveChannel(makeChannel(radioID: Self.radioA, index: 0, name: "A-channel", secret: Data(repeating: 0xA2, count: 16)))
        try await store.saveChannel(makeChannel(radioID: Self.radioB, index: 0, name: "B-channel", secret: Data(repeating: 0xB2, count: 16)))

        let contactsA = try await store.fetchContacts(radioID: Self.radioA)
        let channelsA = try await store.fetchChannels(radioID: Self.radioA)

        #expect(contactsA.map(\.name) == ["A-contact"])
        #expect(channelsA.map(\.name) == ["A-channel"])
        #expect(contactsA.allSatisfy { $0.radioID == Self.radioA })
        #expect(channelsA.allSatisfy { $0.radioID == Self.radioA })
    }

    @Test func fetchIsEmptyForUnseededRadio() async throws {
        // A `nil` current radio short-circuits to `[]` before any fetch; an
        // unknown-but-non-nil radio likewise yields no rows.
        let store = try makeStore()
        try await store.saveContact(makeContact(radioID: Self.radioA, publicKey: Data(repeating: 0xA1, count: 32), name: "A-contact"))

        let stranger = UUID()
        #expect(try await store.fetchContacts(radioID: stranger).isEmpty)
        #expect(try await store.fetchChannels(radioID: stranger).isEmpty)
    }

    private func channelID(radioID: UUID, secret: Data) -> String {
        formatCompositeID(radioID: radioID, kind: .channel, keyHex: channelSecretDigestHex(radioID: radioID, secret: secret))
    }

    @Test func channelResolveFailsSafeOnDuplicateDigest() async throws {
        let store = try makeStore()
        let secret = Data(repeating: 0x09, count: 16)
        // Two distinct rows sharing a secret collide to one digest id; the resolver
        // must refuse both rather than mis-route a send to an arbitrary one.
        try await store.saveChannel(makeChannel(radioID: Self.radioA, index: 0, name: "Dup0", secret: secret))
        try await store.saveChannel(makeChannel(radioID: Self.radioA, index: 1, name: "Dup1", secret: secret))

        let id = channelID(radioID: Self.radioA, secret: secret)
        let resolved = resolveUniqueChannels(matching: [id], in: try await store.fetchChannels(radioID: Self.radioA))

        #expect(resolved.isEmpty)
    }

    @Test func channelResolveFailsSafeOnZeroMatch() async throws {
        let store = try makeStore()
        try await store.saveChannel(makeChannel(radioID: Self.radioA, index: 0, name: "Only", secret: Data(repeating: 0x01, count: 16)))

        let unknownID = channelID(radioID: Self.radioA, secret: Data(repeating: 0xFE, count: 16))
        let resolved = resolveUniqueChannels(matching: [unknownID], in: try await store.fetchChannels(radioID: Self.radioA))

        #expect(resolved.isEmpty)
    }

    @Test func channelResolveAcceptsUniqueDigest() async throws {
        let store = try makeStore()
        let secret = Data(repeating: 0x33, count: 16)
        try await store.saveChannel(makeChannel(radioID: Self.radioA, index: 0, name: "Wanted", secret: secret))
        try await store.saveChannel(makeChannel(radioID: Self.radioA, index: 1, name: "Other", secret: Data(repeating: 0x44, count: 16)))

        let id = channelID(radioID: Self.radioA, secret: secret)
        let resolved = resolveUniqueChannels(matching: [id], in: try await store.fetchChannels(radioID: Self.radioA))

        #expect(resolved.map { MessageTargetEntity(dto: $0).id } == [id])
        #expect(resolved.map(\.name) == ["Wanted"])
    }

    // MARK: - Merged resolution keeps the channel fail-safe

    @Test func mergedResolveExcludesDuplicateDigestChannel() async throws {
        // The same duplicate-digest fail-safe must survive inside the merged
        // contact+channel resolution, not just the channel helper, so a future
        // collapse-to-one-filter refactor of the query fails loudly here.
        let store = try makeStore()
        let secret = Data(repeating: 0x07, count: 16)
        try await store.saveChannel(makeChannel(radioID: Self.radioA, index: 0, name: "Dup0", secret: secret))
        try await store.saveChannel(makeChannel(radioID: Self.radioA, index: 1, name: "Dup1", secret: secret))

        let id = channelID(radioID: Self.radioA, secret: secret)
        let resolved = resolveMessageTargets(
            matching: [id],
            contacts: [],
            channels: try await store.fetchChannels(radioID: Self.radioA)
        )

        #expect(resolved.isEmpty)
    }

    // MARK: - Picker list is chat-only

    @Test func pickerListExcludesRepeaterAndRoomContacts() {
        let contacts = [
            makeContact(radioID: Self.radioA, publicKey: Data(repeating: 0x01, count: 32), name: "Person", type: .chat),
            makeContact(radioID: Self.radioA, publicKey: Data(repeating: 0x02, count: 32), name: "Repeater", type: .repeater),
            makeContact(radioID: Self.radioA, publicKey: Data(repeating: 0x03, count: 32), name: "Room", type: .room)
        ]
        let channels = [makeChannel(radioID: Self.radioA, index: 0, name: "Ops", secret: Data(repeating: 0x0A, count: 16))]

        let targets = buildMessageTargets(contacts: chatContacts(contacts), channels: channels)

        let contactNames = targets.filter { $0.kind == .contact }.map(\.displayName)
        #expect(contactNames == ["Person"])
        #expect(targets.contains { $0.kind == .channel && $0.displayName == "Ops" })
    }

    @Test func pickerListExcludesDuplicateDigestChannels() {
        // Two slots sharing a secret collide to one unresolvable id. Listing
        // either would offer a target the send path refuses with invalidRecipient,
        // so the picker must drop both and keep only the uniquely resolvable one.
        let sharedSecret = Data(repeating: 0x0B, count: 16)
        let channels = [
            makeChannel(radioID: Self.radioA, index: 0, name: "DupA", secret: sharedSecret),
            makeChannel(radioID: Self.radioA, index: 1, name: "DupB", secret: sharedSecret),
            makeChannel(radioID: Self.radioA, index: 2, name: "Unique", secret: Data(repeating: 0x0C, count: 16))
        ]

        let targets = buildMessageTargets(contacts: [], channels: channels)

        #expect(targets.filter { $0.kind == .channel }.map(\.displayName) == ["Unique"])
    }

    // MARK: - Scope fail-safe (nil radio yields nil scope)

    @Test func scopeIsNilWhenBridgeHasNoAppState() {
        // A pre-launch bridge that was never adopted has a nil appState, so the
        // scope resolves nil and every caller maps that to an empty result.
        let bridge = IntentBridge()
        #expect(currentRadioScope(bridge)?.radioID == nil)
    }

    @Test func scopeIsNilWhenAppStateHasNoCurrentRadio() {
        // A fresh AppState with no connection has a nil currentRadioID, so even an
        // adopted bridge resolves no scope rather than reading the wrong radio.
        let bridge = IntentBridge()
        let appState = AppState()
        bridge.adopt(appState)

        #expect(appState.currentRadioID == nil)
        #expect(currentRadioScope(bridge)?.radioID == nil)
    }
}
