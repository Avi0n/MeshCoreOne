import Foundation
@testable import MC1Services
import Testing

@Suite("HashtagUtilities Tests")
struct HashtagUtilitiesTests {
  // MARK: - Regex Pattern Tests

  @Test(arguments: ["#general", "#General", "#test-channel", "#abc123", "#a"])
  func `hashtag pattern matches valid hashtags`(text: String) throws {
    let regex = try NSRegularExpression(pattern: HashtagUtilities.hashtagPattern)
    let range = NSRange(text.startIndex..., in: text)
    let matches = regex.matches(in: text, range: range)
    #expect(matches.count == 1, "Expected match for: \(text)")
  }

  @Test(arguments: ["#test_underscore", "#test.dot", "#", "#-bad", "#bad!", "#white space"])
  func `hashtag pattern rejects invalid hashtags`(text: String) throws {
    // Use anchored pattern for full-string validation (extraction pattern finds partial matches)
    let anchoredPattern = "^" + HashtagUtilities.hashtagPattern + "$"
    let regex = try NSRegularExpression(pattern: anchoredPattern)
    let range = NSRange(text.startIndex..., in: text)
    let matches = regex.matches(in: text, range: range)
    #expect(matches.isEmpty, "Expected no match for: \(text)")
  }

  // MARK: - extractHashtags Tests

  @Test
  func `extractHashtags finds single hashtag`() {
    let result = HashtagUtilities.extractHashtags(from: "Join #general today")
    #expect(result.count == 1)
    #expect(result.first?.name == "#general")
  }

  @Test
  func `extractHashtags accepts uppercase hashtags`() {
    let result = HashtagUtilities.extractHashtags(from: "Join #General today")
    #expect(result.count == 1)
    #expect(result.first?.name == "#General")
  }

  @Test
  func `extractHashtags finds multiple hashtags`() {
    let result = HashtagUtilities.extractHashtags(from: "Try #one and #two")
    #expect(result.count == 2)
    #expect(result[0].name == "#one")
    #expect(result[1].name == "#two")
  }

  @Test
  func `extractHashtags returns empty for no hashtags`() {
    let result = HashtagUtilities.extractHashtags(from: "No hashtags here")
    #expect(result.isEmpty)
  }

  @Test
  func `extractHashtags excludes hashtags inside URLs`() {
    let result = HashtagUtilities.extractHashtags(from: "See https://example.com#section and #general")
    #expect(result.count == 1)
    #expect(result.first?.name == "#general")
  }

  @Test
  func `extractHashtags handles hashtag at end with punctuation`() {
    let result = HashtagUtilities.extractHashtags(from: "Join #general.")
    #expect(result.count == 1)
    #expect(result.first?.name == "#general")
  }

  @Test
  func `extractHashtags handles adjacent hashtags`() {
    let result = HashtagUtilities.extractHashtags(from: "#one#two")
    #expect(result.count == 2)
  }

  // MARK: - isValidHashtagName Tests

  @Test
  func `isValidHashtagName accepts valid names`() {
    #expect(HashtagUtilities.isValidHashtagName("general"))
    #expect(HashtagUtilities.isValidHashtagName("General"))
    #expect(HashtagUtilities.isValidHashtagName("TEST"))
    #expect(HashtagUtilities.isValidHashtagName("test-channel"))
    #expect(HashtagUtilities.isValidHashtagName("abc123"))
    #expect(HashtagUtilities.isValidHashtagName("a"))
  }

  @Test
  func `isValidHashtagName rejects invalid names`() {
    #expect(!HashtagUtilities.isValidHashtagName(""))
    #expect(!HashtagUtilities.isValidHashtagName("-bad"))
    #expect(!HashtagUtilities.isValidHashtagName("test_underscore"))
    #expect(!HashtagUtilities.isValidHashtagName("test.dot"))
    #expect(!HashtagUtilities.isValidHashtagName("bad!"))
  }

  // MARK: - normalizeHashtagName Tests

  @Test
  func `normalizeHashtagName lowercases and strips prefix`() {
    #expect(HashtagUtilities.normalizeHashtagName("#General") == "general")
    #expect(HashtagUtilities.normalizeHashtagName("#TEST") == "test")
    #expect(HashtagUtilities.normalizeHashtagName("general") == "general")
  }

  @Test
  func `sanitizeHashtagNameInput lowercases and strips invalid characters`() {
    #expect(HashtagUtilities.sanitizeHashtagNameInput("General") == "general")
    #expect(HashtagUtilities.sanitizeHashtagNameInput("-General") == "general")
    #expect(HashtagUtilities.sanitizeHashtagNameInput("gen_eral") == "general")
  }
}
