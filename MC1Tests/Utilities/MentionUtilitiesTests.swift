import Foundation
@testable import MC1Services
import Testing

@Suite("MentionUtilities Tests")
struct MentionUtilitiesTests {
  // MARK: - createMention Tests

  @Test
  func `createMention creates correct format`() {
    let mention = MentionUtilities.createMention(for: "Alice")
    #expect(mention == "@[Alice]")
  }

  @Test
  func `createMention handles names with spaces`() {
    let mention = MentionUtilities.createMention(for: "My Node")
    #expect(mention == "@[My Node]")
  }

  @Test
  func `createMention handles special characters`() {
    let mention = MentionUtilities.createMention(for: "Node-123")
    #expect(mention == "@[Node-123]")
  }

  // MARK: - appendMention Tests

  @Test
  func `appendMention into empty draft yields just the mention`() {
    let result = MentionUtilities.appendMention(for: "Alice", to: "")
    #expect(result == "@[Alice] ")
  }

  @Test
  func `appendMention preserves draft and adds a separating space`() {
    let result = MentionUtilities.appendMention(for: "Alice", to: "hello")
    #expect(result == "hello @[Alice] ")
  }

  @Test
  func `appendMention does not double the space when draft ends in whitespace`() {
    let result = MentionUtilities.appendMention(for: "Alice", to: "hello ")
    #expect(result == "hello @[Alice] ")
  }

  @Test
  func `createMention handles empty name`() {
    let mention = MentionUtilities.createMention(for: "")
    #expect(mention == "@[]")
  }

  // MARK: - extractMentions Tests

  @Test
  func `extractMentions parses single mention`() {
    let mentions = MentionUtilities.extractMentions(from: "@[Alice] hello!")
    #expect(mentions == ["Alice"])
  }

  @Test
  func `extractMentions parses multiple mentions`() {
    let mentions = MentionUtilities.extractMentions(from: "@[Alice] and @[Bob] hello!")
    #expect(mentions == ["Alice", "Bob"])
  }

  @Test
  func `extractMentions returns empty for no mentions`() {
    let mentions = MentionUtilities.extractMentions(from: "Hello world!")
    #expect(mentions.isEmpty)
  }

  @Test
  func `extractMentions handles names with spaces`() {
    let mentions = MentionUtilities.extractMentions(from: "@[My Node] says hi")
    #expect(mentions == ["My Node"])
  }

  @Test
  func `extractMentions handles special characters`() {
    let mentions = MentionUtilities.extractMentions(from: "@[Node-123] testing")
    #expect(mentions == ["Node-123"])
  }

  @Test
  func `extractMentions handles adjacent mentions`() {
    let mentions = MentionUtilities.extractMentions(from: "@[Alice]@[Bob]")
    #expect(mentions == ["Alice", "Bob"])
  }

  @Test
  func `extractMentions ignores malformed patterns`() {
    // Missing closing bracket
    let mentions1 = MentionUtilities.extractMentions(from: "@[Alice hello")
    #expect(mentions1.isEmpty)

    // Missing opening bracket
    let mentions2 = MentionUtilities.extractMentions(from: "@Alice] hello")
    #expect(mentions2.isEmpty)

    // Just @ symbol
    let mentions3 = MentionUtilities.extractMentions(from: "@ hello")
    #expect(mentions3.isEmpty)
  }

  @Test
  func `extractMentions handles empty message`() {
    let mentions = MentionUtilities.extractMentions(from: "")
    #expect(mentions.isEmpty)
  }

  @Test
  func `extractMentions handles Unicode names`() {
    let mentions = MentionUtilities.extractMentions(from: "@[日本語] hello")
    #expect(mentions == ["日本語"])
  }

  // MARK: - detectActiveMention Tests

  @Test
  func `detectActiveMention returns nil for empty text`() {
    let result = MentionUtilities.detectActiveMention(in: "")
    #expect(result == nil)
  }

  @Test
  func `detectActiveMention returns nil for text without @`() {
    let result = MentionUtilities.detectActiveMention(in: "hello world")
    #expect(result == nil)
  }

  @Test
  func `detectActiveMention returns empty string for @ alone`() {
    let result = MentionUtilities.detectActiveMention(in: "@")
    #expect(result == "")
  }

  @Test
  func `detectActiveMention returns query after @`() {
    let result = MentionUtilities.detectActiveMention(in: "@jo")
    #expect(result == "jo")
  }

  @Test
  func `detectActiveMention works at start of message`() {
    let result = MentionUtilities.detectActiveMention(in: "@alice")
    #expect(result == "alice")
  }

  @Test
  func `detectActiveMention works after space`() {
    let result = MentionUtilities.detectActiveMention(in: "hey @bob")
    #expect(result == "bob")
  }

  @Test
  func `detectActiveMention returns nil for @ mid-word`() {
    let result = MentionUtilities.detectActiveMention(in: "email@domain")
    #expect(result == nil)
  }

  @Test
  func `detectActiveMention returns nil when space follows @`() {
    let result = MentionUtilities.detectActiveMention(in: "@ hello")
    #expect(result == nil)
  }

  @Test
  func `detectActiveMention returns last active mention`() {
    let result = MentionUtilities.detectActiveMention(in: "@[Alice] hey @bo")
    #expect(result == "bo")
  }

  @Test
  func `detectActiveMention returns nil for completed mention`() {
    let result = MentionUtilities.detectActiveMention(in: "@[Alice] hello")
    #expect(result == nil)
  }

  @Test
  func `detectActiveMention handles Unicode`() {
    let result = MentionUtilities.detectActiveMention(in: "@日本")
    #expect(result == "日本")
  }

  @Test
  func `detectActiveMention ignores email addresses`() {
    let result = MentionUtilities.detectActiveMention(in: "contact me at test@example.com")
    #expect(result == nil)
  }

  @Test
  func `detectActiveMention handles double @ symbols`() {
    let result = MentionUtilities.detectActiveMention(in: "@@alice")
    #expect(result == nil)
  }

  @Test
  func `detectActiveMention returns nil for unclosed bracket`() {
    let result = MentionUtilities.detectActiveMention(in: "@[Alice")
    #expect(result == nil)
  }

  // MARK: - filterContacts Tests

  private func makeContact(
    name: String,
    type: ContactType = .chat,
    publicKey: Data = Data([0xAB])
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: publicKey,
      name: name,
      typeRawValue: type.rawValue,
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
      unreadCount: 0
    )
  }

  @Test
  func `filterContacts matches using localizedStandardContains`() {
    let contacts = [
      makeContact(name: "Alice"),
      makeContact(name: "Bob"),
      makeContact(name: "Amanda")
    ]
    let filtered = MentionUtilities.filterContacts(contacts, query: "a")
    #expect(filtered.count == 2)
    #expect(filtered.map(\.name).contains("Alice"))
    #expect(filtered.map(\.name).contains("Amanda"))
  }

  @Test
  func `filterContacts excludes repeaters`() {
    let contacts = [
      makeContact(name: "Alice", type: .chat),
      makeContact(name: "Repeater1", type: .repeater)
    ]
    let filtered = MentionUtilities.filterContacts(contacts, query: "")
    #expect(filtered.count == 1)
    #expect(filtered.first?.name == "Alice")
  }

  @Test
  func `filterContacts excludes rooms`() {
    let contacts = [
      makeContact(name: "Alice", type: .chat),
      makeContact(name: "Room1", type: .room)
    ]
    let filtered = MentionUtilities.filterContacts(contacts, query: "")
    #expect(filtered.count == 1)
  }

  @Test
  func `filterContacts sorts alphabetically`() {
    let contacts = [
      makeContact(name: "Zoe"),
      makeContact(name: "Alice"),
      makeContact(name: "Bob")
    ]
    let filtered = MentionUtilities.filterContacts(contacts, query: "")
    #expect(filtered.map(\.name) == ["Alice", "Bob", "Zoe"])
  }

  @Test
  func `filterContacts returns empty for no matches`() {
    let contacts = [makeContact(name: "Alice")]
    let filtered = MentionUtilities.filterContacts(contacts, query: "xyz")
    #expect(filtered.isEmpty)
  }

  @Test
  func `filterContacts handles empty input`() {
    let filtered = MentionUtilities.filterContacts([], query: "a")
    #expect(filtered.isEmpty)
  }

  @Test
  func `filterContacts sorts by sender order when provided`() {
    let contacts = [
      makeContact(name: "Alice"),
      makeContact(name: "Bob"),
      makeContact(name: "Charlie")
    ]
    let senderOrder: [String: UInt32] = [
      "Charlie": 300,
      "Alice": 200,
      "Bob": 100
    ]
    let filtered = MentionUtilities.filterContacts(contacts, query: "", senderOrder: senderOrder)
    #expect(filtered.map(\.name) == ["Charlie", "Alice", "Bob"])
  }

  @Test
  func `filterContacts sender order partial match: ordered first, then alphabetical`() {
    let contacts = [
      makeContact(name: "Zoe"),
      makeContact(name: "Alice"),
      makeContact(name: "Bob"),
      makeContact(name: "Dan")
    ]
    // Only Bob and Alice have timestamps; Zoe and Dan don't
    let senderOrder: [String: UInt32] = [
      "Bob": 500,
      "Alice": 100
    ]
    let filtered = MentionUtilities.filterContacts(contacts, query: "", senderOrder: senderOrder)
    // Bob (500) first, Alice (100) second, then Dan and Zoe alphabetically
    #expect(filtered.map(\.name) == ["Bob", "Alice", "Dan", "Zoe"])
  }

  // MARK: - containsSelfMention Tests

  @Test
  func `containsSelfMention returns true for exact match`() {
    let result = MentionUtilities.containsSelfMention(in: "Hello @[Alice]!", selfName: "Alice")
    #expect(result == true)
  }

  @Test
  func `containsSelfMention is case insensitive`() {
    #expect(MentionUtilities.containsSelfMention(in: "@[ALICE]", selfName: "alice"))
    #expect(MentionUtilities.containsSelfMention(in: "@[alice]", selfName: "ALICE"))
    #expect(MentionUtilities.containsSelfMention(in: "@[Alice]", selfName: "aLiCe"))
  }

  @Test
  func `containsSelfMention returns false for different name`() {
    let result = MentionUtilities.containsSelfMention(in: "@[Bob] hello", selfName: "Alice")
    #expect(result == false)
  }

  @Test
  func `containsSelfMention handles multiple mentions`() {
    // Self mention is second
    #expect(MentionUtilities.containsSelfMention(in: "@[Bob] @[Alice]", selfName: "Alice"))
    // Self mention is first
    #expect(MentionUtilities.containsSelfMention(in: "@[Alice] @[Bob]", selfName: "Alice"))
  }

  @Test
  func `containsSelfMention returns false for empty text`() {
    let result = MentionUtilities.containsSelfMention(in: "", selfName: "Alice")
    #expect(result == false)
  }

  @Test
  func `containsSelfMention returns false for empty selfName`() {
    let result = MentionUtilities.containsSelfMention(in: "@[Alice]", selfName: "")
    #expect(result == false)
  }

  @Test
  func `containsSelfMention handles names with spaces`() {
    let result = MentionUtilities.containsSelfMention(in: "@[My Node] hello", selfName: "My Node")
    #expect(result == true)
  }

  @Test
  func `containsSelfMention handles special characters`() {
    let result = MentionUtilities.containsSelfMention(in: "@[Node-123] test", selfName: "Node-123")
    #expect(result == true)
  }

  @Test
  func `containsSelfMention returns false for partial match`() {
    // "Ali" should not match "Alice"
    let result = MentionUtilities.containsSelfMention(in: "@[Ali]", selfName: "Alice")
    #expect(result == false)
  }

  @Test
  func `containsSelfMention returns false for text without mentions`() {
    let result = MentionUtilities.containsSelfMention(in: "Hello world", selfName: "Alice")
    #expect(result == false)
  }
}
