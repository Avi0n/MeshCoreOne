import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Hashtag Channel Navigation Tests")
struct HashtagChannelNavigationTests {
  // MARK: - Secret Derivation Consistency Tests

  @Test
  func `normalized names produce consistent secrets`() {
    // All should normalize to "general" and produce same passphrase
    let name1 = HashtagUtilities.normalizeHashtagName("#General")
    let name2 = HashtagUtilities.normalizeHashtagName("#GENERAL")
    let name3 = HashtagUtilities.normalizeHashtagName("general")
    let name4 = HashtagUtilities.normalizeHashtagName("#general")

    #expect(name1 == name2)
    #expect(name2 == name3)
    #expect(name3 == name4)

    // The passphrase should be "#general" (lowercase with prefix)
    let passphrase = "#\(name1)"
    #expect(passphrase == "#general")
  }

  // MARK: - URL Scheme Tests

  @Test
  func `URL scheme encodes and decodes channel name correctly`() {
    let channelName = "general"
    let url = URL(string: "meshcoreone://hashtag/\(channelName)")

    #expect(url?.scheme == "meshcoreone")
    #expect(url?.host == "hashtag")
    #expect(url?.pathComponents.dropFirst().first == channelName)
  }
}
