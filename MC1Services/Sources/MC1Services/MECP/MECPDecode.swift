/// The MECP wire grammar. A string that does not match returns nil.
public enum MECPDecode {
  public static func decode(_ input: String) -> MECPMessage? {
    guard let envelope = parseEnvelope(input) else { return nil }
    let codes = envelope.codes.split(separator: " ").map(String.init)
    return MECPMessage(
      severity: envelope.severity,
      codes: codes,
      freetext: envelope.freetext.map(String.init)
    )
  }

  static func matchesEnvelope(_ input: String) -> Bool {
    parseEnvelope(input) != nil
  }

  private static func parseEnvelope(
    _ input: String
  ) -> (severity: MECPMessage.Severity, codes: Substring, freetext: Substring?)? {
    guard input.utf8.starts(with: wirePrefix.utf8) else { return nil }
    let stripped = strippingLineEnding(input)
    let scalars = stripped.unicodeScalars
    // Reject trailing space/tab and embedded line breaks up front so long malformed inputs cannot backtrack.
    guard let last = scalars.last, last != " ", last != "\t", !scalars.contains(where: isLineBreak) else {
      return nil
    }
    guard let match = stripped.wholeMatch(of: messagePattern),
          let digit = Int(match.1),
          let severity = MECPMessage.Severity(rawValue: digit) else {
      return nil
    }
    return (severity, match.2, match.3)
  }

  /// MECP 1.4.0 §6.1: a severity, space-separated codes, and optional free text without line breaks
  /// or leading/trailing space or tab. Matched by Unicode scalar so CRLF is two scalars.
  private static var messagePattern: Regex<(Substring, Substring, Substring, Substring?)> {
    #/^MECP/([0-3])/([A-Z][0-9]{2}(?: [A-Z][0-9]{2})*)(?: ([^ \t\n\f\r\u0085\u2028\u2029](?:[^\n\f\r\u0085\u2028\u2029]*[^ \t\n\f\r\u0085\u2028\u2029])?))?$/#
      .matchingSemantics(.unicodeScalar)
  }

  /// MECP §6.1: before matching, a decoder removes at most one trailing line ending: `\n`, `\r` or `\r\n`.
  private static func strippingLineEnding(_ input: String) -> Substring {
    var scalars = input[...].unicodeScalars
    if scalars.last == "\n" { scalars.removeLast() }
    if scalars.last == "\r" { scalars.removeLast() }
    return Substring(scalars)
  }

  private static func isLineBreak(_ scalar: Unicode.Scalar) -> Bool {
    switch scalar.value {
    case 0x000A, 0x000C, 0x000D, 0x0085, 0x2028, 0x2029:
      true
    default:
      false
    }
  }

  struct Extraction: Equatable, Sendable {
    var personCount: Int?
    var timestampHHMM: String?
    var eta: Int?
    var displayTail: String?
  }

  static func extracted(from freetext: String?, codes: [String]) -> Extraction {
    guard let freetext else { return Extraction() }
    var claimed = RangeSet<String.Index>()
    var removed = RangeSet<String.Index>()
    var extraction = Extraction()

    // Claim the app designator, timestamp, person count, then duration. A later match skips characters an
    // earlier one claimed. The designator stays in the tail.
    if let designator = appDesignator(in: freetext, codes: codes) {
      claimed.insert(contentsOf: designator)
    }

    if let match = firstTimestamp(in: freetext, excluding: claimed) {
      extraction.timestampHHMM = match.digits
      claimed.insert(contentsOf: match.range)
      removed.insert(contentsOf: match.range)
    }

    if let pax = firstPax(in: freetext, excluding: claimed) {
      extraction.personCount = pax.count
      claimed.insert(contentsOf: pax.range)
      removed.insert(contentsOf: pax.range)
    }

    if codes.contains(etaCode), let match = duration(in: freetext, excluding: claimed) {
      extraction.eta = match.value
      claimed.insert(contentsOf: match.range)
      removed.insert(contentsOf: match.range)
    }

    extraction.displayTail = removing(removed, from: freetext)
    return extraction
  }

  private static let wirePrefix = "MECP/"
  private static let maxHour = 23
  static let clockPartDigits = 2
  static let asciiZero: UInt32 = 0x30
  static let asciiFive: UInt32 = 0x35
  private static let asciiNine: UInt32 = 0x39

  /// MECP 1.4.0 §6.1 person count decoding pattern, without the boundaries `isBounded` checks.
  private static var paxPattern: Regex<(Substring, Substring)> {
    #/0*([1-9][0-9]*) ?[Pp][Aa][Xx]/#.matchingSemantics(.unicodeScalar)
  }

  /// MECP 1.4.0 §6.1 timestamp decoding pattern, without the boundaries `isBounded` checks. The two-digit
  /// hours come first so `@2300` matches whole rather than as `@230` followed by `0`.
  private static var timestampPattern: Regex<(Substring, Substring, Substring)> {
    #/@(2[0-3]|[01][0-9]|[0-9]):?([0-5][0-9])/#.matchingSemantics(.unicodeScalar)
  }

  static func overlaps(_ claimed: RangeSet<String.Index>, _ range: Range<String.Index>) -> Bool {
    claimed.ranges.contains { $0.overlaps(range) }
  }

  /// The first bounded person count that passes Additional Validation and misses `claimed`.
  /// A rejected candidate resumes the search; an `Int` overflow stops it so the text stays in the tail.
  private static func firstPax(
    in text: String,
    excluding claimed: RangeSet<String.Index>
  ) -> (count: Int, range: Range<String.Index>)? {
    var cursor = text.startIndex
    while cursor < text.endIndex, let match = text[cursor...].firstMatch(of: paxPattern) {
      let digits = text[match.range.lowerBound..<match.1.endIndex]
      if isBounded(match.range, in: text),
         !overlaps(claimed, match.range),
         isValidPax(digits: digits, before: match.range.lowerBound, in: text) {
        guard let count = Int(match.1) else { return nil }
        return (count, match.range)
      }
      cursor = match.range.upperBound
    }
    return nil
  }

  static func isASCIIDigit(_ scalar: Unicode.Scalar) -> Bool {
    scalar.value >= asciiZero && scalar.value <= asciiNine
  }

  private static func isValidPax(digits: Substring, before start: String.Index, in text: String) -> Bool {
    !isDecimalFraction(before: start, in: text)
      && !isColonClockMinutes(digits: digits, before: start, in: text)
  }

  /// A digit, then `.` or `,`, marks a fraction or a coordinate rather than a person count.
  static func isDecimalFraction(before start: String.Index, in text: String) -> Bool {
    let scalars = text.unicodeScalars
    guard start > scalars.startIndex else { return false }
    let sepIndex = scalars.index(before: start)
    let sep = scalars[sepIndex]
    guard sep == "." || sep == ",", sepIndex > scalars.startIndex else { return false }
    return isASCIIDigit(scalars[scalars.index(before: sepIndex)])
  }

  /// Minutes of an `H:MM` or `HH:MM` clock, with or without `@`, are not a person count when the
  /// hour is 0...23 and is not part of a longer digit run.
  static func isColonClockMinutes(
    digits: Substring,
    before start: String.Index,
    in text: String
  ) -> Bool {
    let digitScalars = digits.unicodeScalars
    guard digitScalars.count == clockPartDigits else { return false }
    let tens = digitScalars[digitScalars.startIndex]
    let ones = digitScalars[digitScalars.index(after: digitScalars.startIndex)]
    guard tens.value >= asciiZero, tens.value <= asciiFive, isASCIIDigit(ones) else {
      return false
    }

    let scalars = text.unicodeScalars
    guard start > scalars.startIndex else { return false }
    let colonIndex = scalars.index(before: start)
    guard scalars[colonIndex] == ":", colonIndex > scalars.startIndex else { return false }

    var hourStart = scalars.index(before: colonIndex)
    guard isASCIIDigit(scalars[hourStart]) else { return false }
    if hourStart > scalars.startIndex {
      let prev = scalars.index(before: hourStart)
      if isASCIIDigit(scalars[prev]) {
        hourStart = prev
      }
    }
    if hourStart > scalars.startIndex, isASCIIDigit(scalars[scalars.index(before: hourStart)]) {
      return false
    }
    guard let hour = Int(text[hourStart..<colonIndex]) else { return false }
    return hour <= maxHour
  }

  /// MECP 1.4.0 §6.1 rule 4: the 66-scalar boundary class B.
  private static func isBoundary(_ scalar: Unicode.Scalar) -> Bool {
    switch scalar.value {
    case 0x0009, 0x000A, 0x000C, 0x000D, 0x0020: // ASCII whitespace
      true
    case 0x0021, 0x0022, 0x0027, 0x0028, 0x0029, 0x002C, 0x002E, 0x003A, 0x003B, 0x003F, 0x005B, 0x005D, 0x007B, 0x007D: // ASCII punctuation
      true
    case 0x00A0, 0x1680, 0x2000...0x200A, 0x202F, 0x205F, 0x3000: // Unicode spaces
      true
    case 0x060C, 0x061B, 0x061F, 0x06D4: // Arabic punctuation
      true
    case 0x00AB, 0x00BB, 0x2013, 0x2014, 0x2018...0x201D, 0x2026: // Latin & typographic punctuation
      true
    case 0x3001, 0x3002, 0x300C...0x300F, 0x3010, 0x3011: // CJK punctuation & brackets
      true
    case 0xFF01, 0xFF08, 0xFF09, 0xFF0C, 0xFF0E, 0xFF1A, 0xFF1B, 0xFF1F: // Fullwidth punctuation
      true
    default:
      false
    }
  }

  /// MECP 1.4.0 §6.1 rule 4: a decoding match needs the start of the free text or one B scalar before
  /// it, and one B scalar or the end of the free text after it.
  static func isBounded(_ range: Range<String.Index>, in text: String) -> Bool {
    let scalars = text.unicodeScalars
    let before = range.lowerBound == scalars.startIndex || isBoundary(scalars[scalars.index(before: range.lowerBound)])
    let after = range.upperBound == scalars.endIndex || isBoundary(scalars[range.upperBound])
    return before && after
  }

  /// The first bounded timestamp that misses `claimed`, as four digits. A rejected match has only one `@`, at
  /// its start, so resuming after it does not skip a later timestamp. An out-of-range value such as `@2460`
  /// does not match.
  private static func firstTimestamp(
    in text: String,
    excluding claimed: RangeSet<String.Index>
  ) -> (digits: String, range: Range<String.Index>)? {
    var cursor = text.startIndex
    while cursor < text.endIndex, let match = text[cursor...].firstMatch(of: timestampPattern) {
      if isBounded(match.range, in: text), !overlaps(claimed, match.range) {
        let hour = match.1.count == 1 ? "0\(match.1)" : String(match.1)
        return (hour + match.2, match.range)
      }
      cursor = match.range.upperBound
    }
    return nil
  }

  private static func removing(_ subranges: RangeSet<String.Index>, from text: String) -> String? {
    guard !subranges.isEmpty else {
      return text.allSatisfy(\.isWhitespace) ? nil : text
    }
    var result = text
    result.removeSubranges(subranges)
    let collapsed = result
      .split(separator: " ", omittingEmptySubsequences: true)
      .joined(separator: " ")
    let trimmed = collapsed.drop(while: \.isWhitespace)
    var end = trimmed.endIndex
    while end > trimmed.startIndex, trimmed[trimmed.index(before: end)].isWhitespace {
      end = trimmed.index(before: end)
    }
    let tail = trimmed[..<end]
    return tail.isEmpty ? nil : String(tail)
  }
}
