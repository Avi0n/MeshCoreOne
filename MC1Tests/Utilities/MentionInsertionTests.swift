import Foundation
@testable import MC1Services
import Testing

@Suite("Mention Insertion Tests")
struct MentionInsertionTests {
  @Test
  func `insertMention replaces @query with mention format`() throws {
    var text = "hey @ali"
    let query = try #require(MentionUtilities.detectActiveMention(in: text))
    let searchPattern = "@" + query

    if let range = text.range(of: searchPattern, options: .backwards) {
      let mention = MentionUtilities.createMention(for: "Alice")
      text.replaceSubrange(range, with: mention + " ")
    }

    #expect(text == "hey @[Alice] ")
  }

  @Test
  func `insertMention handles query at start of text`() throws {
    var text = "@bob"
    let query = try #require(MentionUtilities.detectActiveMention(in: text))
    let searchPattern = "@" + query

    if let range = text.range(of: searchPattern, options: .backwards) {
      let mention = MentionUtilities.createMention(for: "Bob")
      text.replaceSubrange(range, with: mention + " ")
    }

    #expect(text == "@[Bob] ")
  }

  @Test
  func `insertMention preserves preceding text`() throws {
    var text = "Hello @[Alice] and @jo"
    let query = try #require(MentionUtilities.detectActiveMention(in: text))
    let searchPattern = "@" + query

    if let range = text.range(of: searchPattern, options: .backwards) {
      let mention = MentionUtilities.createMention(for: "John")
      text.replaceSubrange(range, with: mention + " ")
    }

    #expect(text == "Hello @[Alice] and @[John] ")
  }

  @Test
  func `insertMention uses contact name not nickname`() throws {
    // Simulates: user searches "Bob" (nickname), selects contact with name "Bob's Solar Node"
    var text = "@bob"
    let contactNodeName = "Bob's Solar Node"

    let query = try #require(MentionUtilities.detectActiveMention(in: text))
    let searchPattern = "@" + query

    if let range = text.range(of: searchPattern, options: .backwards) {
      let mention = MentionUtilities.createMention(for: contactNodeName)
      text.replaceSubrange(range, with: mention + " ")
    }

    #expect(text == "@[Bob's Solar Node] ")
  }
}
