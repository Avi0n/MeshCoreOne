import CoreLocation
import MC1Services
import SwiftUI

/// Detects every link kind in one pass over the normalized message string and merges the
/// results into a single sorted, non-overlapping `[LinkToken]`. Each detector runs exactly
/// once; overlaps are resolved by the fixed `LinkToken.Kind` priority, replacing the old
/// linkifier's threaded `urlRanges`/`linkRanges`/`excludedRanges` snapshots and its
/// reverse-iteration index bookkeeping.
enum MessageLinkTokenizer {
  /// Style inputs threaded from the live theme/contrast environment for the kinds the
  /// tokenizer detects directly (URL, meshcore link, hashtag, coordinate). Mention and
  /// contact-share colors are already resolved by the pre-pass.
  struct StyleContext {
    let baseColor: Color
    let isOutgoing: Bool
    let outgoingTextColor: Color
    let hashtagColor: Color
  }

  struct Result {
    let tokens: [LinkToken]
    /// The first surviving coordinate in document order, used to drive the map preview.
    let mapCoordinate: CLLocationCoordinate2D?
  }

  /// One shared http/https detector. `HashtagUtilities` receives this detector's ranges
  /// rather than running a second `NSDataDetector`.
  private static let urlDetector: NSDataDetector? = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)

  private static let meshCoreLinkRegex = try? NSRegularExpression(pattern: #"meshcore://[^\s<>"]+"#)

  /// A coordinate detection paired with its parsed value, so the map coordinate can be
  /// read from the first surviving coordinate token after the overlap merge.
  private struct CoordinateHit {
    let token: LinkToken
    let coordinate: CLLocationCoordinate2D
  }

  static func tokenize(
    normalized: String,
    preSpans: [LinkToken],
    context: StyleContext
  ) -> Result {
    // One http/https scan feeds both the URL tokens and the ranges the hashtag detector
    // uses to skip a `#fragment` that lives inside a URL.
    let urls = detectURLs(in: normalized)

    var hits: [LinkToken] = preSpans
    hits.append(contentsOf: urls.map { urlToken(range: $0.range, url: $0.url, context: context) })
    hits.append(contentsOf: detectMeshCoreLinkTokens(in: normalized, context: context))
    hits.append(contentsOf: detectHashtagTokens(in: normalized, urlRanges: urls.map(\.range), context: context))

    let coordinateHits = detectCoordinateHits(in: normalized, context: context)
    hits.append(contentsOf: coordinateHits.map(\.token))

    let merged = resolveOverlaps(hits)

    // The map preview follows the first coordinate that survived overlap resolution, in
    // document order. A coordinate shadowed by a higher-priority token (a contact chip,
    // say) does not drive the preview.
    let survivingCoordinateRanges = Set(merged.lazy.filter { $0.kind == .coordinate }.map(\.range))
    let mapCoordinate = coordinateHits
      .filter { survivingCoordinateRanges.contains($0.token.range) }
      .min { $0.token.range.lowerBound < $1.token.range.lowerBound }?
      .coordinate

    return Result(tokens: merged, mapCoordinate: mapCoordinate)
  }

  // MARK: - Overlap resolution

  /// Accepts detections highest priority first, dropping any that overlaps one already
  /// accepted, then returns the survivors in document order. Resolving by `LinkToken.Kind`
  /// priority rather than by start index is what lets a higher-priority kind that begins
  /// inside a lower-priority run still win the overlap: a `hashtag` fragment embedded in a
  /// `meshcoreLink` keeps its hashtag styling and leaves the surrounding link unlinked. The
  /// result is sorted and non-overlapping.
  private static func resolveOverlaps(_ tokens: [LinkToken]) -> [LinkToken] {
    let byPriority = tokens.sorted { lhs, rhs in
      if lhs.kind != rhs.kind {
        return lhs.kind < rhs.kind
      }
      return lhs.range.lowerBound < rhs.range.lowerBound
    }

    var accepted: [LinkToken] = []
    for token in byPriority where !accepted.contains(where: { $0.range.overlaps(token.range) }) {
      accepted.append(token)
    }
    return accepted.sorted { $0.range.lowerBound < $1.range.lowerBound }
  }

  // MARK: - URLs

  private struct DetectedURL {
    let range: Range<String.Index>
    let url: URL
  }

  private static func detectURLs(in text: String) -> [DetectedURL] {
    guard let detector = urlDetector else { return [] }
    let nsRange = NSRange(text.startIndex..., in: text)
    return detector.matches(in: text, options: [], range: nsRange).compactMap { match in
      guard let url = match.url,
            let scheme = url.scheme?.lowercased(),
            scheme == "http" || scheme == "https",
            let range = Range(match.range, in: text) else { return nil }
      return DetectedURL(range: range, url: url)
    }
  }

  private static func urlToken(range: Range<String.Index>, url: URL, context: StyleContext) -> LinkToken {
    LinkToken(
      range: range,
      kind: .url,
      url: url,
      foregroundColor: context.baseColor,
      backgroundColor: nil,
      underline: true,
      bold: false
    )
  }

  // MARK: - MeshCore links

  private static func detectMeshCoreLinkTokens(in text: String, context: StyleContext) -> [LinkToken] {
    guard let regex = meshCoreLinkRegex else { return [] }
    let nsRange = NSRange(text.startIndex..., in: text)
    return regex.matches(in: text, range: nsRange).compactMap { match in
      guard var matchRange = Range(match.range, in: text) else { return nil }

      // Strip trailing punctuation the regex may over-capture.
      while let last = text[matchRange].last, ".,;:!?)".contains(last) {
        matchRange = matchRange.lowerBound..<text.index(before: matchRange.upperBound)
        if matchRange.isEmpty { break }
      }
      guard !matchRange.isEmpty,
            let url = URL(string: String(text[matchRange])),
            url.host() == "contact" || url.host() == "channel" else { return nil }

      return LinkToken(
        range: matchRange,
        kind: .meshcoreLink,
        url: url,
        foregroundColor: context.baseColor,
        backgroundColor: nil,
        underline: true,
        bold: false
      )
    }
  }

  // MARK: - Hashtags

  private static func detectHashtagTokens(
    in text: String,
    urlRanges: [Range<String.Index>],
    context: StyleContext
  ) -> [LinkToken] {
    let hashtags = HashtagUtilities.extractHashtags(from: text, urlRanges: urlRanges)
    return hashtags.compactMap { hashtag in
      let channelName = HashtagUtilities.normalizeHashtagName(hashtag.name)
      guard let url = URL(string: "meshcoreone://hashtag/\(channelName)") else { return nil }
      return LinkToken(
        range: hashtag.range,
        kind: .hashtag,
        url: url,
        foregroundColor: context.isOutgoing ? context.outgoingTextColor : context.hashtagColor,
        backgroundColor: nil,
        underline: false,
        bold: true
      )
    }
  }

  // MARK: - Coordinates

  private static func detectCoordinateHits(in text: String, context: StyleContext) -> [CoordinateHit] {
    ChatCoordinateDetector.matches(in: text).compactMap { match in
      guard let url = mapURL(
        latitude: match.coordinate.latitude,
        longitude: match.coordinate.longitude
      ) else { return nil }
      let token = LinkToken(
        range: match.range,
        kind: .coordinate,
        url: url,
        foregroundColor: context.baseColor,
        backgroundColor: nil,
        underline: true,
        bold: false
      )
      return CoordinateHit(token: token, coordinate: match.coordinate)
    }
  }

  /// Builds `meshcore://map?lat=&lon=` with locale-independent `%.6f` values so the link
  /// round-trips through `MeshCoreURLParser.parseMapURL` on every locale. A comma-decimal
  /// locale's `.formatted()` would emit `37,334900`, which the parser's decimal gate rejects.
  private static func mapURL(latitude: Double, longitude: Double) -> URL? {
    var components = URLComponents()
    components.scheme = MeshCoreURLParser.scheme
    components.host = "map"
    components.queryItems = [
      URLQueryItem(name: "lat", value: String(format: "%.6f", latitude)),
      URLQueryItem(name: "lon", value: String(format: "%.6f", longitude)),
    ]
    return components.url
  }
}
