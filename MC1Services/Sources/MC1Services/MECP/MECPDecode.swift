/// The MECP wire grammar. A string that does not match returns nil.
public enum MECPDecode {
  public static func decode(_ input: String) -> MECPMessage? {
    guard let match = removingLineEnding(input).wholeMatch(of: messagePattern),
          let digit = Int(match.1),
          let severity = MECPMessage.Severity(rawValue: digit) else {
      return nil
    }
    let codes = match.2.split(separator: " ").map(String.init)
    return MECPMessage(severity: severity, codes: codes, freetext: match.3.map(String.init))
  }

  /// MECP 1.4.0 §6.1, as written in the protocol's freetext/patterns.json: a severity, one or more
  /// codes separated by single spaces, and optional free text after one more space that does not
  /// begin or end with a space or tab and has no line break in it. Matched by Unicode scalar, as the
  /// pattern is in other languages: by `Character`, a CRLF is one character that the line-break
  /// classes do not exclude.
  ///
  /// Marked `nonisolated(unsafe)` because `Regex` is not `Sendable`; it is an immutable value.
  private nonisolated(unsafe) static let messagePattern = #/^MECP/([0-3])/([A-Z][0-9]{2}(?: [A-Z][0-9]{2})*)(?: ([^ \t\n\f\r\u0085\u2028\u2029](?:[^\n\f\r\u0085\u2028\u2029]*[^ \t\n\f\r\u0085\u2028\u2029])?))?$/#
    .matchingSemantics(.unicodeScalar)

  /// MECP §6.1: before matching, a decoder removes at most one trailing line ending: `\n`, `\r` or `\r\n`.
  private static func removingLineEnding(_ input: String) -> String {
    var scalars = Substring(input).unicodeScalars
    if scalars.last == "\n" { scalars.removeLast() }
    if scalars.last == "\r" { scalars.removeLast() }
    return String(scalars)
  }

  struct Extraction: Equatable {
    var personCount: Int?
    var timestampHHMM: String?
    var eta: Int?
    var displayTail: String?
  }

  static func extracted(from freetext: String?, codes: [String]) -> Extraction {
    guard let freetext else { return Extraction() }
    var removal: [Range<String.Index>] = []
    var extraction = Extraction()

    let pax = firstPax(in: freetext)
    if let pax {
      extraction.personCount = pax.count
      removal.append(pax.range)
    }

    if let match = firstTimestamp(in: freetext), isAcceptedClock(match.digits) {
      extraction.timestampHHMM = match.digits
      removal.append(match.range)
    }

    // mecp-org/MECP#48: a bare eta that is the number of a person count at the same start is not an eta.
    if codes.contains(etaCode), let match = leadingEta(in: freetext),
       pax?.range.lowerBound != match.range.lowerBound {
      extraction.eta = match.minutes
      removal.append(match.range)
    }

    extraction.displayTail = removing(removal, from: freetext)
    return extraction
  }

  private static let etaCode = "R03"
  private static let maxHour = 23
  private static let maxMinute = 59
  private static let clockDigits = 4

  private static func isASCIIDigit(_ character: Character) -> Bool {
    character.isASCII && character.isNumber
  }

  /// MECP 1.4.0 §6.1 person count decoding pattern, without the boundaries `isBounded` checks.
  private nonisolated(unsafe) static let paxPattern = #/0*([1-9][0-9]*) ?[Pp][Aa][Xx]/#
    .matchingSemantics(.unicodeScalar)

  /// The first bounded person count. A zero count does not match, so a later value is used. Checking the
  /// boundaries after matching finds the same value as the wrapped pattern, because the only boundary a
  /// match can contain is the space before `pax`, so no bounded match can start inside a rejected one.
  private static func firstPax(in text: String) -> (count: Int, range: Range<String.Index>)? {
    guard let match = text.matches(of: paxPattern).first(where: { isBounded($0.range, in: text) }) else {
      return nil
    }
    // The pattern does not limit the digits; a count too large for Int is clamped, not dropped.
    return (Int(match.1) ?? Int.max, match.range)
  }

  /// MECP 1.4.0 §6.1 rule 4: the boundary class B.
  private static let boundary: Set<Unicode.Scalar> = {
    let listed = "\t\n\u{0C}\r ,.;:!?'\"()[]{}"
      + "\u{00A0}\u{1680}\u{202F}\u{205F}\u{3000}"
      + "\u{060C}\u{061B}\u{061F}\u{06D4}"
      + "\u{00AB}\u{00BB}\u{2013}\u{2014}\u{2026}"
      + "\u{3001}\u{3002}\u{3010}\u{3011}"
      + "\u{FF01}\u{FF08}\u{FF09}\u{FF0C}\u{FF0E}\u{FF1A}\u{FF1B}\u{FF1F}"
    let ranges: [ClosedRange<UInt32>] = [0x2000...0x200A, 0x2018...0x201D, 0x300C...0x300F]
    return Set(listed.unicodeScalars).union(ranges.joined().compactMap(Unicode.Scalar.init))
  }()

  /// MECP 1.4.0 §6.1 rule 4: a decoding match needs the start of the free text or one B scalar before
  /// it, and one B scalar or the end of the free text after it.
  private static func isBounded(_ range: Range<String.Index>, in text: String) -> Bool {
    let scalars = text.unicodeScalars
    let before = range.lowerBound == scalars.startIndex || boundary.contains(scalars[scalars.index(before: range.lowerBound)])
    let after = range.upperBound == scalars.endIndex || boundary.contains(scalars[range.upperBound])
    return before && after
  }

  private static func firstTimestamp(in text: String) -> (digits: String, range: Range<String.Index>)? {
    var search = text.startIndex
    while search < text.endIndex, let at = text[search...].firstIndex(of: "@") {
      var cursor = text.index(after: at)
      var count = 0
      while cursor < text.endIndex, count < clockDigits, isASCIIDigit(text[cursor]) {
        cursor = text.index(after: cursor)
        count += 1
      }
      let atBoundary = cursor == text.endIndex || text[cursor] == " "
      if count == clockDigits, atBoundary {
        let digitsStart = text.index(after: at)
        return (String(text[digitsStart..<cursor]), at..<cursor)
      }
      search = text.index(after: at)
    }
    return nil
  }

  private static func leadingEta(in text: String) -> (minutes: Int, range: Range<String.Index>)? {
    var index = text.startIndex
    guard index < text.endIndex, isASCIIDigit(text[index]) else { return nil }
    let start = index
    while index < text.endIndex, isASCIIDigit(text[index]) {
      index = text.index(after: index)
    }
    guard index == text.endIndex || text[index] == " ", let minutes = Int(text[start..<index]) else { return nil }
    return (minutes, start..<index)
  }

  private static func isAcceptedClock(_ digits: String) -> Bool {
    guard digits.count == 4,
          let hour = Int(digits.prefix(2)),
          let minute = Int(digits.suffix(2)) else {
      return false
    }
    return hour <= maxHour && minute <= maxMinute
  }

  private static func removing(_ ranges: [Range<String.Index>], from text: String) -> String? {
    var result = text
    for range in ranges.sorted(by: { $0.lowerBound > $1.lowerBound }) {
      result.removeSubrange(range)
    }
    let collapsed = result.split(separator: " ", omittingEmptySubsequences: true).joined(separator: " ")
    return collapsed.isEmpty ? nil : collapsed
  }
}
