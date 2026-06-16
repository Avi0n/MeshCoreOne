import Testing
import SwiftUI
import CoreLocation
@testable import MC1Services
@testable import MC1

/// Exercises the tokenizer directly: each detector in isolation, the documented priority
/// resolving overlaps, the non-overlap invariant, first-coordinate `mapCoordinate` derivation,
/// and the `%.6f` round-trip through `MeshCoreURLParser`.
@Suite("MessageLinkTokenizer Tests")
@MainActor
struct MessageLinkTokenizerTests {

    private let context = MessageLinkTokenizer.StyleContext(
        baseColor: .primary,
        isOutgoing: false,
        outgoingTextColor: .white,
        hashtagColor: .blue
    )

    /// Tokenizes a plain string with no pre-pass spans.
    private func tokenize(_ text: String) -> MessageLinkTokenizer.Result {
        MessageLinkTokenizer.tokenize(normalized: text, preSpans: [], context: context)
    }

    private func token(_ result: MessageLinkTokenizer.Result, covering substring: String, in text: String) -> LinkToken? {
        guard let range = text.range(of: substring) else { return nil }
        return result.tokens.first { $0.range.overlaps(range) }
    }

    // MARK: - Detectors in isolation

    @Test("URL detector emits a url token")
    func urlDetector() {
        let text = "see https://example.com here"
        let result = tokenize(text)
        let token = token(result, covering: "https://example.com", in: text)
        #expect(token?.kind == .url)
        #expect(token?.url?.scheme == "https")
        #expect(token?.underline == true)
        #expect(token?.bold == false)
    }

    @Test("Hashtag detector emits a bold, non-underlined token")
    func hashtagDetector() {
        let text = "join #general today"
        let result = tokenize(text)
        let token = token(result, covering: "#general", in: text)
        #expect(token?.kind == .hashtag)
        #expect(token?.url?.absoluteString == "meshcoreone://hashtag/general")
        #expect(token?.bold == true)
        #expect(token?.underline == false)
    }

    @Test("MeshCore link detector emits a meshcore token and trims trailing punctuation")
    func meshcoreLinkDetector() {
        let text = "open meshcore://contact/add?name=Bob."
        let result = tokenize(text)
        let token = token(result, covering: "meshcore://contact", in: text)
        #expect(token?.kind == .meshcoreLink)
        #expect(token?.url?.host() == "contact")
        // The trailing period is excluded from the link range.
        if let range = token?.range {
            #expect(text[range].last != ".")
        }
    }

    @Test("MeshCore link with a non-contact, non-channel host is ignored")
    func meshcoreLinkRejectsUnknownHost() {
        let text = "open meshcore://other/path"
        let result = tokenize(text)
        #expect(result.tokens.isEmpty)
    }

    @Test("Coordinate detector emits a coordinate token")
    func coordinateDetector() {
        let text = "at 37.7749, -122.4194 now"
        let result = tokenize(text)
        let token = token(result, covering: "37.7749, -122.4194", in: text)
        #expect(token?.kind == .coordinate)
        #expect(token?.url?.host() == "map")
        #expect(token?.underline == true)
    }

    // MARK: - Priority and overlap

    @Test("A pre-pass mention span outranks a URL detection over the same characters")
    func mentionOutranksURL() {
        // Pre-pass produces a mention span over "@WCMesh.com"; the URL detector also fires on
        // "WCMesh.com". Priority keeps the mention and drops the url.
        let normalized = "Hey @WCMesh.com hi"
        guard let spanRange = normalized.range(of: "@WCMesh.com") else {
            Issue.record("span substring not found")
            return
        }
        let preSpan = LinkToken(
            range: spanRange,
            kind: .mention,
            url: URL(string: "meshcoreone://mention/WCMesh.com"),
            foregroundColor: .primary,
            backgroundColor: nil,
            underline: true,
            bold: false
        )
        let result = MessageLinkTokenizer.tokenize(normalized: normalized, preSpans: [preSpan], context: context)

        let covering = result.tokens.filter { $0.range.overlaps(spanRange) }
        #expect(covering.count == 1)
        #expect(covering.first?.kind == .mention)
        #expect(covering.first?.url?.scheme == "meshcoreone")
    }

    @Test("The merged token stream is sorted and non-overlapping")
    func nonOverlapInvariant() {
        let text = "@x see https://a.com #ops at 37.7749, -122.4194 end"
        let result = tokenize(text)
        let tokens = result.tokens
        for index in tokens.indices.dropLast() {
            let current = tokens[index]
            let next = tokens[index + 1]
            #expect(current.range.lowerBound <= next.range.lowerBound, "tokens must be sorted")
            #expect(!current.range.overlaps(next.range), "tokens must not overlap")
        }
    }

    @Test("A hashtag inside a URL is excluded via the shared URL ranges")
    func hashtagInsideURLExcluded() {
        let text = "Check https://example.com#general and #ops"
        let result = tokenize(text)
        // The #general fragment lives inside the URL and must not become a hashtag token.
        let insideURL = token(result, covering: "#general", in: text)
        #expect(insideURL?.kind == .url)
        // The standalone #ops is still a hashtag.
        let standalone = token(result, covering: "#ops", in: text)
        #expect(standalone?.kind == .hashtag)
    }

    @Test("A hashtag inside a meshcore link wins the overlap and the meshcore link is dropped")
    func hashtagOutranksMeshcoreLink() {
        let text = "meshcore://channel/add?name=ops#general"
        let result = tokenize(text)
        // The embedded #general resolves to a hashtag token; the surrounding meshcore link
        // loses the overlap and contributes no token, leaving its text unlinked.
        let hashtag = token(result, covering: "#general", in: text)
        #expect(hashtag?.kind == .hashtag)
        #expect(!result.tokens.contains { $0.kind == .meshcoreLink })
    }

    // MARK: - mapCoordinate derivation

    @Test("mapCoordinate is the first surviving coordinate in document order")
    func mapCoordinateFirstInOrder() {
        let text = "first 37.7749, -122.4194 then 10.0, 20.0"
        let result = tokenize(text)
        let coordinate = result.mapCoordinate
        #expect(coordinate != nil)
        #expect(abs((coordinate?.latitude ?? 0) - 37.7749) < 0.0001)
        #expect(abs((coordinate?.longitude ?? 0) - (-122.4194)) < 0.0001)
    }

    @Test("mapCoordinate skips a coordinate that lost to a higher-priority token")
    func mapCoordinateSkipsOverlapped() {
        // The first coordinate sits inside a contact-share pre-pass span, so it does not survive
        // and must not drive the map coordinate; the second standalone coordinate does.
        let normalized = "Base 1.0, 2.0 then 3.5, 4.5"
        guard let chipRange = normalized.range(of: "Base 1.0, 2.0") else {
            Issue.record("chip substring not found")
            return
        }
        let preSpan = LinkToken(
            range: chipRange,
            kind: .contactShare,
            url: URL(string: "meshcore://contact/add?name=Base"),
            foregroundColor: .primary,
            backgroundColor: nil,
            underline: true,
            bold: false
        )
        let result = MessageLinkTokenizer.tokenize(normalized: normalized, preSpans: [preSpan], context: context)
        let coordinate = result.mapCoordinate
        #expect(coordinate != nil)
        #expect(abs((coordinate?.latitude ?? 0) - 3.5) < 0.0001)
        #expect(abs((coordinate?.longitude ?? 0) - 4.5) < 0.0001)
    }

    @Test("No coordinate yields a nil mapCoordinate")
    func mapCoordinateNilWhenAbsent() {
        let result = tokenize("no coordinates here at all")
        #expect(result.mapCoordinate == nil)
    }

    // MARK: - Round-trip

    @Test("A coordinate token URL round-trips through MeshCoreURLParser with dot-decimals")
    func coordinateRoundTrips() throws {
        let text = "37.334900, -122.009020"
        let result = tokenize(text)
        let token = try #require(token(result, covering: text, in: text))
        let url = try #require(token.url)
        let coordinate = try #require(MeshCoreURLParser.parseMapURL(url.absoluteString))
        #expect(abs(coordinate.latitude - 37.3349) < 0.000001)
        #expect(abs(coordinate.longitude - (-122.00902)) < 0.000001)
        #expect(url.absoluteString.contains("lat=37.334900"))
        #expect(url.absoluteString.contains("lon=-122.009020"))
    }
}
