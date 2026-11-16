import Foundation
@testable import MC1Services
import Testing

@Suite("Text Rules preference")
struct TextRulesPreferenceTests {
  @Test
  func `type defaults and explicit choices are independent`() {
    var preference = TextRulesPreference()
    let member = TextRulesPreference.Key(radioID: UUID(), conversationID: UUID())
    let otherRadio = TextRulesPreference.Key(radioID: UUID(), conversationID: member.conversationID)

    for kind in TextRulesPreference.Kind.allCases {
      let onByDefault = kind == .publicChannel || kind == .hashtagChannel
      #expect(preference.selection(for: kind) == (onByDefault ? .all : .off))
      #expect(preference.effective(for: kind, key: nil, override: .inherit).isOn == onByDefault)
      #expect(preference.effective(for: kind, key: member, override: .on) == .init(isOn: true, source: .explicit))
      #expect(preference.effective(for: kind, key: member, override: .off) == .init(isOn: false, source: .explicit))

      preference.setSelection(.fixed([member]), for: kind)
      #expect(preference.effective(for: kind, key: member, override: .inherit) == .init(isOn: true, source: .type))
      #expect(preference.effective(for: kind, key: otherRadio, override: .inherit).source == .fixedExclusion)
      #expect(preference.effective(for: kind, key: nil, override: .inherit).source == .fixedExclusion)

      preference.setSelection(.fixed([]), for: kind)
      #expect(preference.selection(for: kind).isOn)
      #expect(!preference.effective(for: kind, key: member, override: .inherit).isOn)
    }
  }

  @Test
  func `comparison is literal and case insensitive across independent fields`() throws {
    let first = TextRulesPreference.Rule(phrase: "CAFÉ*", lookIn: .messageOrSender, match: .contains)
    let second = TextRulesPreference.Rule(phrase: "alert", lookIn: .message, match: .contains)
    var preference = TextRulesPreference()
    preference.append(first)
    preference.append(second)

    let body = "Alert café* café*"
    let sender = "café* store"
    let matched = try #require(preference.firstMatch(body: body, sender: sender))
    #expect(matched.rule == first)
    #expect(matched.bodyHighlights.compactMap { $0.range(in: body).map { String(body[$0]) } } == ["café*", "café*"])
    #expect(matched.senderHighlights.compactMap { $0.range(in: sender).map { String(sender[$0]) } } == ["café*"])
    #expect(preference.firstMatch(body: "cafe*", sender: nil) == nil)
    #expect(preference.firstMatch(body: "cafe\u{301}*", sender: nil) == nil)
    #expect(preference.firstMatch(body: "caféx", sender: nil) == nil)
    #expect(preference.firstMatch(body: "", sender: nil) == nil)
    #expect(preference.firstMatch(body: nil, sender: "alert") == nil)
    #expect(preference.firstMatch(body: nil, sender: sender)?.rule == first)
  }

  @Test
  func `whole field keeps significant spaces and substring highlights expand to characters`() throws {
    var preference = TextRulesPreference()
    let whole = TextRulesPreference.Rule(phrase: " ALERT", lookIn: .messageOrSender, match: .wholeField)
    preference.append(whole)
    #expect(preference.firstMatch(body: " alert", sender: "ALERT")?.rule == whole)
    #expect(preference.firstMatch(body: "  alert", sender: "ALERT") == nil)
    #expect(preference.firstMatch(body: " alert ", sender: nil) == nil)

    preference.append(.init(phrase: "👩", lookIn: .sender, match: .contains))
    let sender = "Radio 👩‍🚒"
    let match = try #require(preference.firstMatch(body: nil, sender: sender))
    let range = try #require(match.senderHighlights.first?.range(in: sender))
    #expect(sender[range] == "👩‍🚒")
  }

  @Test @MainActor
  func `settings edits persist and failed saves leave committed values intact`() throws {
    let directory = try makeDirectory()
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appending(path: "rules.json")
    let manager = try makeManager(url: url)
    manager.loadTextRules()

    let rule = TextRulesPreference.Rule(phrase: " alert ", lookIn: .message, match: .contains)
    try manager.updateTextRules { $0.append(rule) }
    try manager.updateTextRules { $0.setSelection(.fixed([]), for: .publicChannel) }
    let committed = try #require(manager.textRulesPreference)
    #expect(committed.rules == [rule])
    #expect(committed.selection(for: .publicChannel) == .fixed([]))

    let reopened = try makeManager(url: url)
    reopened.loadTextRules()
    #expect(reopened.textRulesPreference == committed)
    let committedBytes = try Data(contentsOf: url)
    #expect(throws: CocoaError.self) {
      try manager.updateTextRules { $0.append(.init(phrase: " \n", lookIn: .sender, match: .contains)) }
    }
    #expect(try Data(contentsOf: url) == committedBytes)

    try FileManager.default.removeItem(at: url)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
    #expect(throws: CocoaError.self) {
      try manager.updateTextRules { $0.removeRule(id: rule.id) }
    }
    #expect(manager.textRulesPreference == committed)
  }

  @Test @MainActor
  func `unreadable preference bytes survive until successful retry`() async throws {
    let directory = try makeDirectory()
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appending(path: "rules.json")
    let malformed = Data("invalid settings".utf8)
    try malformed.write(to: url)

    let manager = try makeManager(url: url)
    manager.loadTextRules()
    #expect(manager.textRulesPreference == nil)
    #expect(!manager.isTextRulesLoading)
    #expect(throws: CocoaError.self) {
      try manager.updateTextRules { $0.setSelection(.all, for: .room) }
    }
    await manager.retryTextRulesLoad()
    #expect(try Data(contentsOf: url) == malformed)
    #expect(manager.textRulesPreference == nil)

    let recovered = TextRulesPreference()
    try JSONEncoder().encode(recovered).write(to: url)
    await manager.retryTextRulesLoad()
    #expect(manager.textRulesPreference == recovered)
    #expect(!manager.isTextRulesLoading)
  }

  private func makeDirectory() throws -> URL {
    let directory = URL.temporaryDirectory.appending(path: UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return directory
  }

  @MainActor
  private func makeManager(url: URL) throws -> ConnectionManager {
    try ConnectionManager(
      modelContainer: PersistenceStore.createContainer(inMemory: true),
      stateMachine: MockBLEStateMachine(),
      transport: MockMeshTransport(),
      textRulesURL: url
    )
  }
}
