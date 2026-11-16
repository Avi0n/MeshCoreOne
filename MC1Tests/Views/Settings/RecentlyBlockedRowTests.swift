import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Recently Blocked rows")
@MainActor
struct RecentlyBlockedRowTests {
  @Test
  func `caption names the chat, the phrase, and the rule`() {
    let caption = RecentlyBlockedRow.caption(
      conversationName: "Dock",
      phrase: "spare",
      summary: "Contains · Message"
    )
    #expect(caption == "Dock · “spare” · Contains · Message")
  }

  @Test
  func `caption omits a missing chat`() {
    let caption = RecentlyBlockedRow.caption(
      conversationName: nil,
      phrase: "spare",
      summary: "Contains · Message"
    )
    #expect(caption == "“spare” · Contains · Message")
  }

  @Test
  func `several radios keep the newest radio first`() throws {
    let home = UUID()
    let field = UUID()
    let rows = try [
      makeEntry(body: "home-new", radioName: nil, radioID: home, phrase: "home-new", arrivedAt: date(3)),
      makeEntry(body: "field", radioName: nil, radioID: field, phrase: "field", arrivedAt: date(2)),
      makeEntry(body: "home-old", radioName: "Home", radioID: home, phrase: "home-old", arrivedAt: date(1)),
    ]
    let groups = RecentlyBlockedView.radioGroups(in: rows)
    #expect(groups.map(\.radioID) == [home, field])
    #expect(groups.map(\.radioName) == ["Home", nil] as [String?])
    #expect(groups[0].entries.map(\.body) == ["home-new", "home-old"])
    #expect(groups[1].entries.map(\.body) == ["field"])
  }

  @Test
  func `voiceover skips a missing sender and chat`() throws {
    let missing = try makeEntry(body: "bring the spare", sender: nil, conversationName: nil, phrase: "spare")
    let label = RecentlyBlockedRow.accessibilityLabel(for: missing)
    let rule = L10n.Settings.TextRules.ruleSummary(missing.match.rule.phrase, missing.match.rule.summary)
    #expect(!label.contains(L10n.Settings.TextRules.Archive.labelPrefix(L10n.Settings.TextRules.Field.sender)))
    #expect(!label.contains(L10n.Settings.TextRules.Archive.labelPrefix(L10n.Settings.TextRules.Archive.chat)))
    #expect(label.contains(labeled(L10n.Settings.TextRules.Field.message, "bring the spare")))
    #expect(label.contains(labeled(L10n.Settings.TextRules.Archive.rule, rule)))
    #expect(label.contains(L10n.Settings.TextRules.Archive.labelPrefix(L10n.Settings.TextRules.Archive.time)))
  }

  private func labeled(_ label: String, _ value: String) -> String {
    L10n.Settings.TextRules.Archive.labelPrefix(label) + value
  }

  private func date(_ index: Int) -> Date {
    Date(timeIntervalSince1970: Double(index))
  }

  private func makeEntry(
    body: String,
    sender: String? = "Ada",
    conversationName: String? = "Dock",
    radioName: String? = "Home",
    radioID: UUID = UUID(),
    phrase: String = "spare",
    arrivedAt: Date = Date(timeIntervalSince1970: 1_700_000_000)
  ) throws -> RecentlyBlockedMessageDTO {
    var preference = TextRulesPreference()
    preference.append(.init(phrase: phrase, lookIn: .message, match: .contains))
    let match = try #require(preference.firstMatch(body: body, sender: sender))
    return RecentlyBlockedMessageDTO(
      identity: .init(
        radioID: radioID,
        conversationID: UUID(),
        senderPrefix: nil,
        senderTimestamp: 1,
        textType: 0,
        wireText: Data(body.utf8)
      ),
      arrivedAt: arrivedAt,
      body: body,
      sender: sender,
      conversationName: conversationName,
      radioName: radioName,
      match: match
    )
  }
}
