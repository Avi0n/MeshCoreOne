import CoreLocation
import Foundation

/// Single source of truth for detecting decimal-degree coordinate pairs in
/// message text. The linkifier (`MessageText.applyCoordinateFormatting`) and the
/// map-preview fragment builder both route through this type, so the tappable
/// text link and the thumbnail never disagree about what counts as a coordinate.
///
/// The `-90...90` / `-180...180` clamp is the sole validity gate for the
/// thumbnail path: the thumbnail forwards the parsed coordinate straight to the
/// snapshotter camera and `navigateToMap`, neither of which re-validates.
enum ChatCoordinateDetector {
    struct Match {
        let range: Range<String.Index>
        let coordinate: CLLocationCoordinate2D
    }

    private static let latitudeRange = -90.0...90.0
    private static let longitudeRange = -180.0...180.0

    // swiftlint:disable force_try
    /// Matches a decimal-degree pair: each component is a sign-optional 1-3 digit
    /// integer part with a required decimal point and at least one fractional digit.
    /// The lookbehind excludes an adjacent word char or `.` so the pass does not match
    /// inside a longer number or after a version prefix (`v1.2, 3.4`). The trailing
    /// lookahead rejects a following version segment (`.digit`, as in `3.4.5`) or word
    /// char, but allows trailing punctuation so a coordinate ending a sentence still matches.
    /// `try!` is intentional: the pattern is a literal, so an init failure here
    /// is a programmer error that must crash at first use rather than silently
    /// disable coordinate linking (no log, no test signal).
    private static let coordinateRegex = try! NSRegularExpression(
        pattern: #"(?<![\w.])(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)(?!\.\d)(?!\w)"#
    )
    // swiftlint:enable force_try

    /// All valid coordinate matches in document order. Applies the regex, the
    /// range clamp, and the decimal-list guard. Already-linked-range skipping is
    /// the linkifier's concern (it needs the `AttributedString`) and is not done here.
    static func matches(in text: String) -> [Match] {
        // Common-case fast path: a coordinate pair must contain a comma. Skips
        // NSRange construction and regex engine entry for the vast majority of
        // bodies, which matters during full chat rebuilds (theme toggle, env
        // flip) that re-run this over hundreds of messages.
        guard text.contains(",") else { return [] }
        let nsRange = NSRange(text.startIndex..., in: text)
        var results: [Match] = []
        for match in coordinateRegex.matches(in: text, range: nsRange) {
            guard match.numberOfRanges == 3,
                  let fullRange = Range(match.range, in: text),
                  let latRange = Range(match.range(at: 1), in: text),
                  let lonRange = Range(match.range(at: 2), in: text),
                  let latitude = Double(String(text[latRange])),
                  let longitude = Double(String(text[lonRange])),
                  latitudeRange.contains(latitude),
                  longitudeRange.contains(longitude) else { continue }

            // Decimal-list guard: a third comma-number following the pair means this
            // is a numeric list (`1.0, 2.0, 3.0`), not a coordinate. Skip it.
            let tail = text[fullRange.upperBound...]
            if tail.range(of: #"\s*,\s*-?\d"#, options: [.regularExpression, .anchored]) != nil {
                continue
            }

            results.append(Match(
                range: fullRange,
                coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
            ))
        }
        return results
    }

    /// The first valid coordinate in document order, or nil.
    static func firstCoordinate(in text: String) -> CLLocationCoordinate2D? {
        matches(in: text).first?.coordinate
    }
}
