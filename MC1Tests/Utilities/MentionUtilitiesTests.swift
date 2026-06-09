import Testing
import Foundation
@testable import MC1Services

@Suite("MentionUtilities Tests")
struct MentionUtilitiesTests {

    // MARK: - createMention Tests

    @Test("createMention creates correct format")
    func testCreateMention() {
        let mention = MentionUtilities.createMention(for: "Alice")
        #expect(mention == "@[Alice]")
    }

    @Test("createMention handles names with spaces")
    func testCreateMentionWithSpaces() {
        let mention = MentionUtilities.createMention(for: "My Node")
        #expect(mention == "@[My Node]")
    }

    @Test("createMention handles special characters")
    func testCreateMentionWithSpecialChars() {
        let mention = MentionUtilities.createMention(for: "Node-123")
        #expect(mention == "@[Node-123]")
    }

    @Test("createMention handles empty name")
    func testCreateMentionEmpty() {
        let mention = MentionUtilities.createMention(for: "")
        #expect(mention == "@[]")
    }

    // MARK: - extractMentions Tests

    @Test("extractMentions parses single mention")
    func testExtractSingleMention() {
        let mentions = MentionUtilities.extractMentions(from: "@[Alice] hello!")
        #expect(mentions == ["Alice"])
    }

    @Test("extractMentions parses multiple mentions")
    func testExtractMultipleMentions() {
        let mentions = MentionUtilities.extractMentions(from: "@[Alice] and @[Bob] hello!")
        #expect(mentions == ["Alice", "Bob"])
    }

    @Test("extractMentions returns empty for no mentions")
    func testExtractNoMentions() {
        let mentions = MentionUtilities.extractMentions(from: "Hello world!")
        #expect(mentions.isEmpty)
    }

    @Test("extractMentions handles names with spaces")
    func testExtractMentionWithSpaces() {
        let mentions = MentionUtilities.extractMentions(from: "@[My Node] says hi")
        #expect(mentions == ["My Node"])
    }

    @Test("extractMentions handles special characters")
    func testExtractMentionWithSpecialChars() {
        let mentions = MentionUtilities.extractMentions(from: "@[Node-123] testing")
        #expect(mentions == ["Node-123"])
    }

    @Test("extractMentions handles adjacent mentions")
    func testExtractAdjacentMentions() {
        let mentions = MentionUtilities.extractMentions(from: "@[Alice]@[Bob]")
        #expect(mentions == ["Alice", "Bob"])
    }

    @Test("extractMentions ignores malformed patterns")
    func testExtractMalformedPatterns() {
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

    @Test("extractMentions handles empty message")
    func testExtractFromEmptyMessage() {
        let mentions = MentionUtilities.extractMentions(from: "")
        #expect(mentions.isEmpty)
    }

    @Test("extractMentions handles Unicode names")
    func testExtractUnicodeMentions() {
        let mentions = MentionUtilities.extractMentions(from: "@[日本語] hello")
        #expect(mentions == ["日本語"])
    }

    // MARK: - detectActiveMention Tests

    @Test("detectActiveMention returns nil for empty text")
    func testDetectActiveMentionEmpty() {
        let result = MentionUtilities.detectActiveMention(in: "")
        #expect(result == nil)
    }

    @Test("detectActiveMention returns nil for text without @")
    func testDetectActiveMentionNoAt() {
        let result = MentionUtilities.detectActiveMention(in: "hello world")
        #expect(result == nil)
    }

    @Test("detectActiveMention returns empty string for @ alone")
    func testDetectActiveMentionAtAlone() {
        let result = MentionUtilities.detectActiveMention(in: "@")
        #expect(result == "")
    }

    @Test("detectActiveMention returns query after @")
    func testDetectActiveMentionBasic() {
        let result = MentionUtilities.detectActiveMention(in: "@jo")
        #expect(result == "jo")
    }

    @Test("detectActiveMention works at start of message")
    func testDetectActiveMentionAtStart() {
        let result = MentionUtilities.detectActiveMention(in: "@alice")
        #expect(result == "alice")
    }

    @Test("detectActiveMention works after space")
    func testDetectActiveMentionAfterSpace() {
        let result = MentionUtilities.detectActiveMention(in: "hey @bob")
        #expect(result == "bob")
    }

    @Test("detectActiveMention returns nil for @ mid-word")
    func testDetectActiveMentionMidWord() {
        let result = MentionUtilities.detectActiveMention(in: "email@domain")
        #expect(result == nil)
    }

    @Test("detectActiveMention returns nil when space follows @")
    func testDetectActiveMentionSpaceAfter() {
        let result = MentionUtilities.detectActiveMention(in: "@ hello")
        #expect(result == nil)
    }

    @Test("detectActiveMention returns last active mention")
    func testDetectActiveMentionMultiple() {
        let result = MentionUtilities.detectActiveMention(in: "@[Alice] hey @bo")
        #expect(result == "bo")
    }

    @Test("detectActiveMention returns nil for completed mention")
    func testDetectActiveMentionCompleted() {
        let result = MentionUtilities.detectActiveMention(in: "@[Alice] hello")
        #expect(result == nil)
    }

    @Test("detectActiveMention handles Unicode")
    func testDetectActiveMentionUnicode() {
        let result = MentionUtilities.detectActiveMention(in: "@日本")
        #expect(result == "日本")
    }

    @Test("detectActiveMention ignores email addresses")
    func testDetectActiveMentionEmail() {
        let result = MentionUtilities.detectActiveMention(in: "contact me at test@example.com")
        #expect(result == nil)
    }

    @Test("detectActiveMention handles double @ symbols")
    func testDetectActiveMentionDoubleAt() {
        let result = MentionUtilities.detectActiveMention(in: "@@alice")
        #expect(result == nil)
    }

    @Test("detectActiveMention returns nil for unclosed bracket")
    func testDetectActiveMentionUnclosedBracket() {
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

    @Test("filterContacts matches using localizedStandardContains")
    func testFilterContactsMatches() {
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

    @Test("filterContacts excludes repeaters")
    func testFilterContactsExcludesRepeaters() {
        let contacts = [
            makeContact(name: "Alice", type: .chat),
            makeContact(name: "Repeater1", type: .repeater)
        ]
        let filtered = MentionUtilities.filterContacts(contacts, query: "")
        #expect(filtered.count == 1)
        #expect(filtered.first?.name == "Alice")
    }

    @Test("filterContacts excludes rooms")
    func testFilterContactsExcludesRooms() {
        let contacts = [
            makeContact(name: "Alice", type: .chat),
            makeContact(name: "Room1", type: .room)
        ]
        let filtered = MentionUtilities.filterContacts(contacts, query: "")
        #expect(filtered.count == 1)
    }

    @Test("filterContacts sorts alphabetically")
    func testFilterContactsSortsAlphabetically() {
        let contacts = [
            makeContact(name: "Zoe"),
            makeContact(name: "Alice"),
            makeContact(name: "Bob")
        ]
        let filtered = MentionUtilities.filterContacts(contacts, query: "")
        #expect(filtered.map(\.name) == ["Alice", "Bob", "Zoe"])
    }

    @Test("filterContacts returns empty for no matches")
    func testFilterContactsNoMatches() {
        let contacts = [makeContact(name: "Alice")]
        let filtered = MentionUtilities.filterContacts(contacts, query: "xyz")
        #expect(filtered.isEmpty)
    }

    @Test("filterContacts handles empty input")
    func testFilterContactsEmptyInput() {
        let filtered = MentionUtilities.filterContacts([], query: "a")
        #expect(filtered.isEmpty)
    }

    @Test("filterContacts sorts by sender order when provided")
    func testFilterContactsSortsBySenderOrder() {
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

    @Test("filterContacts sender order partial match: ordered first, then alphabetical")
    func testFilterContactsSenderOrderPartialMatch() {
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

    @Test("containsSelfMention returns true for exact match")
    func testContainsSelfMentionExact() {
        let result = MentionUtilities.containsSelfMention(in: "Hello @[Alice]!", selfName: "Alice")
        #expect(result == true)
    }

    @Test("containsSelfMention is case insensitive")
    func testContainsSelfMentionCaseInsensitive() {
        #expect(MentionUtilities.containsSelfMention(in: "@[ALICE]", selfName: "alice"))
        #expect(MentionUtilities.containsSelfMention(in: "@[alice]", selfName: "ALICE"))
        #expect(MentionUtilities.containsSelfMention(in: "@[Alice]", selfName: "aLiCe"))
    }

    @Test("containsSelfMention returns false for different name")
    func testContainsSelfMentionDifferentName() {
        let result = MentionUtilities.containsSelfMention(in: "@[Bob] hello", selfName: "Alice")
        #expect(result == false)
    }

    @Test("containsSelfMention handles multiple mentions")
    func testContainsSelfMentionMultiple() {
        // Self mention is second
        #expect(MentionUtilities.containsSelfMention(in: "@[Bob] @[Alice]", selfName: "Alice"))
        // Self mention is first
        #expect(MentionUtilities.containsSelfMention(in: "@[Alice] @[Bob]", selfName: "Alice"))
    }

    @Test("containsSelfMention returns false for empty text")
    func testContainsSelfMentionEmptyText() {
        let result = MentionUtilities.containsSelfMention(in: "", selfName: "Alice")
        #expect(result == false)
    }

    @Test("containsSelfMention returns false for empty selfName")
    func testContainsSelfMentionEmptySelfName() {
        let result = MentionUtilities.containsSelfMention(in: "@[Alice]", selfName: "")
        #expect(result == false)
    }

    @Test("containsSelfMention handles names with spaces")
    func testContainsSelfMentionWithSpaces() {
        let result = MentionUtilities.containsSelfMention(in: "@[My Node] hello", selfName: "My Node")
        #expect(result == true)
    }

    @Test("containsSelfMention handles special characters")
    func testContainsSelfMentionSpecialChars() {
        let result = MentionUtilities.containsSelfMention(in: "@[Node-123] test", selfName: "Node-123")
        #expect(result == true)
    }

    @Test("containsSelfMention returns false for partial match")
    func testContainsSelfMentionPartialMatch() {
        // "Ali" should not match "Alice"
        let result = MentionUtilities.containsSelfMention(in: "@[Ali]", selfName: "Alice")
        #expect(result == false)
    }

    @Test("containsSelfMention returns false for text without mentions")
    func testContainsSelfMentionNoMentions() {
        let result = MentionUtilities.containsSelfMention(in: "Hello world", selfName: "Alice")
        #expect(result == false)
    }
}
