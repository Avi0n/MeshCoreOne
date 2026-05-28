import Testing
import Foundation
@testable import MC1

@Suite("MentionDeeplinkSupport")
struct MentionDeeplinkSupportTests {

    @Test("A plain name round-trips through url and name")
    func plainNameRoundTrips() {
        let url = MentionDeeplinkSupport.url(forName: "Alice Smith")
        #expect(url?.scheme == MentionDeeplinkSupport.scheme)
        #expect(url?.host == MentionDeeplinkSupport.host)
        #expect(MentionDeeplinkSupport.name(from: url!) == "Alice Smith")
    }

    @Test("A name containing a slash round-trips without losing the prefix")
    func slashNameRoundTrips() {
        let original = "Node 1/2 Repeater"
        let url = MentionDeeplinkSupport.url(forName: original)
        #expect(MentionDeeplinkSupport.name(from: url!) == original)
    }

    @Test("A name containing a percent sign round-trips and is not dropped")
    func percentNameRoundTrips() {
        let original = "100% Coverage"
        let url = MentionDeeplinkSupport.url(forName: original)
        #expect(MentionDeeplinkSupport.name(from: url!) == original)
    }

    @Test("A literal percent-escape sequence in the name is not double-decoded")
    func literalEscapeSequenceRoundTrips() {
        let original = "a%2Fb"
        let url = MentionDeeplinkSupport.url(forName: original)
        #expect(MentionDeeplinkSupport.name(from: url!) == original)
    }

    @Test("A unicode name round-trips")
    func unicodeNameRoundTrips() {
        let original = "Café 北京"
        let url = MentionDeeplinkSupport.url(forName: original)
        #expect(MentionDeeplinkSupport.name(from: url!) == original)
    }

    @Test("name returns nil for non-mention URLs")
    func rejectsNonMentionURLs() {
        #expect(MentionDeeplinkSupport.name(from: URL(string: "https://apple.com")!) == nil)
        #expect(MentionDeeplinkSupport.name(from: URL(string: "meshcore://map?lat=1&lon=2")!) == nil)
        #expect(MentionDeeplinkSupport.name(from: URL(string: "meshcoreone://hashtag/general")!) == nil)
    }
}
