import Foundation
@testable import MC1
import Testing

@Suite("MentionDeeplinkSupport")
struct MentionDeeplinkSupportTests {
  @Test
  func `A plain name round-trips through url and name`() throws {
    let url = MentionDeeplinkSupport.url(forName: "Alice Smith")
    #expect(url?.scheme == MentionDeeplinkSupport.scheme)
    #expect(url?.host == MentionDeeplinkSupport.host)
    #expect(try MentionDeeplinkSupport.name(from: #require(url)) == "Alice Smith")
  }

  @Test
  func `A name containing a slash round-trips without losing the prefix`() throws {
    let original = "Node 1/2 Repeater"
    let url = MentionDeeplinkSupport.url(forName: original)
    #expect(try MentionDeeplinkSupport.name(from: #require(url)) == original)
  }

  @Test
  func `A name containing a percent sign round-trips and is not dropped`() throws {
    let original = "100% Coverage"
    let url = MentionDeeplinkSupport.url(forName: original)
    #expect(try MentionDeeplinkSupport.name(from: #require(url)) == original)
  }

  @Test
  func `A literal percent-escape sequence in the name is not double-decoded`() throws {
    let original = "a%2Fb"
    let url = MentionDeeplinkSupport.url(forName: original)
    #expect(try MentionDeeplinkSupport.name(from: #require(url)) == original)
  }

  @Test
  func `A unicode name round-trips`() throws {
    let original = "Café 北京"
    let url = MentionDeeplinkSupport.url(forName: original)
    #expect(try MentionDeeplinkSupport.name(from: #require(url)) == original)
  }

  @Test
  func `name returns nil for non-mention URLs`() throws {
    #expect(try MentionDeeplinkSupport.name(from: #require(URL(string: "https://apple.com"))) == nil)
    #expect(try MentionDeeplinkSupport.name(from: #require(URL(string: "meshcore://map?lat=1&lon=2"))) == nil)
    #expect(try MentionDeeplinkSupport.name(from: #require(URL(string: "meshcoreone://hashtag/general"))) == nil)
  }
}
