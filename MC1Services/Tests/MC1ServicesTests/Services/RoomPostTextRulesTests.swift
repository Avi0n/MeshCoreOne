import Foundation
@testable import MC1Services
import Testing

@Suite("Signed room post text rules")
struct RoomPostTextRulesTests {
  private static let bookmark: UInt32 = 40
  private static let author = Data([0xA1, 0xB2, 0xC3, 0xD4])

  private struct Fixture {
    let store: PersistenceStore
    let room: RemoteNodeSessionDTO
    let service: RoomServerService
    let policy: Policy
    let lifetime: ReceiveScope.Lifetime

    func receive(_ text: String, timestamp: UInt32 = 42) async throws -> RoomMessageDTO? {
      try await service.handleIncomingMessage(
        senderPublicKeyPrefix: Data(room.publicKey.prefix(6)),
        timestamp: timestamp,
        authorPrefix: RoomPostTextRulesTests.author,
        text: text
      )
    }
  }

  private actor Policy {
    private var preference = TextRulesPreference()

    init(_ rule: TextRulesPreference.Rule) {
      preference.setSelection(.all, for: .room)
      preference.append(rule)
    }

    func snapshot() -> TextRulesPreference {
      preference
    }

    func turnOff() {
      preference.setSelection(.off, for: .room)
    }
  }

  private func makeFixture(
    phrase: String,
    lookIn: TextRulesPreference.Rule.LookIn = .message,
    bookmark: UInt32 = Self.bookmark
  ) async throws -> Fixture {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let room = RemoteNodeSessionDTO.testSession(
      radioID: radioID, lastSyncTimestamp: bookmark, lastMessageDate: Date(timeIntervalSince1970: 39)
    )
    try await store.saveRemoteNodeSessionDTO(room)
    let saved = try #require(try await store.fetchRemoteNodeSession(id: room.id))
    let policy = Policy(.init(phrase: phrase, lookIn: lookIn, match: .wholeField))
    let mesh = MockMeshCoreSession()
    let remoteNode = RemoteNodeService(session: mesh, dataStore: store, keychainService: KeychainService())
    let lifetime = ReceiveScope.Lifetime()
    let service = RoomServerService(
      session: mesh, remoteNodeService: remoteNode, dataStore: store, radioID: radioID,
      textRulesPreference: { await policy.snapshot() }, receiveLifetime: lifetime
    )
    return Fixture(store: store, room: saved, service: service, policy: policy, lifetime: lifetime)
  }

  @Test(arguments: [UInt32(0), UInt32(40)])
  func `drop can recover a room without advancing its bookmark or visible activity`(bookmark: UInt32) async throws {
    let fixture = try await makeFixture(phrase: " ALERT ", bookmark: bookmark)
    #expect(try await fixture.receive(" ALERT ") == nil)

    let recovered = try #require(try await fixture.store.fetchRemoteNodeSession(id: fixture.room.id))
    #expect(recovered.isConnected)
    #expect(recovered.lastSyncTimestamp == bookmark)
    #expect(recovered.lastMessageDate == fixture.room.lastMessageDate)
    #expect(recovered.unreadCount == 0)
    #expect(try await fixture.service.fetchMessages(sessionID: fixture.room.id).isEmpty)

    let entries = try await fixture.store.recentlyBlockedMessages()
    #expect(entries.count == 1)
    #expect(entries.first?.body == " ALERT ")
    #expect(entries.first?.identity.conversationID == recovered.textRulesID)

    await fixture.policy.turnOff()
    #expect(try await fixture.receive(" ALERT ") == nil)
    #expect(try await fixture.store.recentlyBlockedCount() == 1)
    #expect(try await fixture.receive(" ALERT", timestamp: 41)?.timestamp == 41)
    let saved = try #require(try await fixture.store.fetchRemoteNodeSession(id: fixture.room.id))
    #expect(saved.lastSyncTimestamp == 41)
    #expect(saved.lastMessageDate != fixture.room.lastMessageDate)
    #expect(saved.unreadCount == 1)
  }

  @Test
  func `short key collision reaches policy and each exact replay keeps its disposition`() async throws {
    let allowed = "collision fixture 58777"
    let hidden = "collision fixture 123682"
    let fixture = try await makeFixture(phrase: hidden)
    #expect(try await fixture.receive(allowed)?.text == allowed)
    #expect(try await fixture.receive(hidden) == nil)
    await fixture.policy.turnOff()
    #expect(try await fixture.receive(allowed) == nil)
    #expect(try await fixture.receive(hidden) == nil)
    #expect(try await fixture.service.fetchMessages(sessionID: fixture.room.id).map(\.text) == [allowed])
    #expect(try await fixture.store.recentlyBlockedMessages().map(\.body) == [hidden])
  }

  @Test
  func `only a unique author on the current Radio ID is available to sender rules`() async throws {
    let fixture = try await makeFixture(phrase: "Match me", lookIn: .sender)
    let foreign = UUID()
    func key(tail: UInt8) -> Data {
      Self.author + Data(repeating: tail, count: 28)
    }

    try await fixture.store.saveContact(.testContact(
      radioID: foreign, publicKey: key(tail: 1), name: "Match me"
    ))
    let foreignOnly = try #require(try await fixture.receive("Foreign display", timestamp: 41))
    #expect(foreignOnly.authorName == "Match me")

    try await fixture.store.saveContact(.testContact(
      radioID: fixture.room.radioID, publicKey: key(tail: 2), name: "Match me"
    ))
    #expect(try await fixture.receive("Unique", timestamp: 42) == nil)

    try await fixture.store.saveContact(.testContact(
      radioID: fixture.room.radioID, publicKey: key(tail: 3), name: "Another author"
    ))
    #expect(try await fixture.receive("Ambiguous", timestamp: 43) != nil)
    #expect(try await fixture.store.recentlyBlockedMessages().map(\.body) == ["Unique"])
  }

  @Test
  func `a retired Link record cannot recover a room or archive its post`() async throws {
    let fixture = try await makeFixture(phrase: "Hidden")
    fixture.lifetime.retire()
    #expect(try await fixture.receive("Hidden") == nil)
    #expect(try await fixture.store.recentlyBlockedCount() == 0)
    #expect(try await fixture.store.fetchRemoteNodeSession(id: fixture.room.id)?.isConnected == false)
  }
}
