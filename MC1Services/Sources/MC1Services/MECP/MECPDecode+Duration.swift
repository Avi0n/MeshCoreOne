/// MECP §6.1 duration (R03 only) and the app designator claim that precedes it.
extension MECPDecode {
  typealias Claim<Value> = (value: Value, range: Range<String.Index>)

  static let etaCode = "R03"

  /// MECP §6.1 rule 9: the app designator applies when any Private Use `U` code is present. MC1 does not
  /// dispatch on it, so it is only claimed: a designator such as `eta` is never re-read as a duration.
  static func appDesignator(in text: String, codes: [String]) -> Range<String.Index>? {
    guard codes.contains(where: { $0.wholeMatch(of: privateUseCodePattern) != nil }),
          let match = text.prefixMatch(of: appDesignatorPattern),
          isBounded(match.range, in: text) else {
      return nil
    }
    return match.range
  }

  /// The R03 duration in minutes. Unit patterns 1–4 are tried first; the earliest valid match wins, and a
  /// tie goes to the earlier pattern. The bare-number pattern 5 applies only when none of them matches.
  static func duration(in text: String, excluding claimed: RangeSet<String.Index>) -> Claim<Int>? {
    var best: Claim<Int>?
    for unit in DurationUnit.allCases {
      guard let candidate = firstDuration(of: unit, in: text, excluding: claimed) else { continue }
      if let current = best, current.range.lowerBound <= candidate.range.lowerBound { continue }
      best = candidate
    }
    return best ?? bareDuration(in: text, excluding: claimed)
  }

  private enum DurationUnit: CaseIterable {
    case hoursMinutes, hours, minutes, days

    /// Group 1 is the unit count; `hoursMinutes` adds the minutes as group 2.
    var pattern: Regex<AnyRegexOutput> {
      switch self {
      case .hoursMinutes:
        Regex(#/(?:[Ee][Tt][Aa] ?)?([0-9]+) ?[Hh] ?([0-9]+) ?[Mm][Ii][Nn]/#.matchingSemantics(.unicodeScalar))
      case .hours:
        Regex(#/(?:[Ee][Tt][Aa] ?)?([0-9]+) ?[Hh]/#.matchingSemantics(.unicodeScalar))
      case .minutes:
        Regex(#/(?:[Ee][Tt][Aa] ?)?([0-9]+) ?[Mm][Ii][Nn]/#.matchingSemantics(.unicodeScalar))
      case .days:
        Regex(#/(?:[Ee][Tt][Aa] ?)?([0-9]+) ?[Dd]/#.matchingSemantics(.unicodeScalar))
      }
    }

    var minutesPerUnit: Int {
      switch self {
      case .hoursMinutes, .hours: minutesPerHour
      case .minutes: 1
      case .days: minutesPerDay
      }
    }
  }

  private static let minutesPerHour = 60
  private static let minutesPerDay = 1440
  private static let countGroup = 1
  private static let minutesGroup = 2

  private static var bareDurationPattern: Regex<(Substring, Substring)> {
    #/^(?:[Ee][Tt][Aa] ?)?([0-9]+)/#.matchingSemantics(.unicodeScalar)
  }

  private static var privateUseCodePattern: Regex<Substring> {
    #/U[0-9]{2}/#.matchingSemantics(.unicodeScalar)
  }

  private static var appDesignatorPattern: Regex<(Substring, Substring)> {
    #/([a-z][a-z0-9-]{0,31})/#.matchingSemantics(.unicodeScalar)
  }

  /// A match without boundaries is not a match, so the search retries one scalar later; a match that fails
  /// a claim or Additional Validation resumes after its end (rule 6).
  private static func firstDuration(
    of unit: DurationUnit,
    in text: String,
    excluding claimed: RangeSet<String.Index>
  ) -> Claim<Int>? {
    let scalars = text.unicodeScalars
    var cursor = scalars.startIndex
    while cursor < scalars.endIndex,
          let match = Substring(scalars[cursor...]).firstMatch(of: unit.pattern) {
      guard isBounded(match.range, in: text) else {
        cursor = scalars.index(after: match.range.lowerBound)
        continue
      }
      let extraMinutes = match.output.count > minutesGroup ? match.output[minutesGroup].substring : nil
      if let count = match.output[countGroup].substring,
         !overlaps(claimed, match.range),
         isValidUnitNumber(count, in: text),
         let minutes = minutes(count, extraMinutes, unit: unit), minutes > 0 {
        return (minutes, match.range)
      }
      cursor = match.range.upperBound
    }
    return nil
  }

  /// Pattern 5 reads only the first freetext token, and never a decimal or the hour of an `HH:MM` clock.
  private static func bareDuration(in text: String, excluding claimed: RangeSet<String.Index>) -> Claim<Int>? {
    guard let match = text.prefixMatch(of: bareDurationPattern),
          !overlaps(claimed, match.range),
          !isFollowedByFractionOrClockMinutes(match.range.upperBound, in: text),
          isBounded(match.range, in: text),
          !isColonClockMinutes(digits: match.1, before: match.1.startIndex, in: text),
          let minutes = Int(match.1), minutes > 0 else {
      return nil
    }
    return (minutes, match.range)
  }

  private static func isValidUnitNumber(_ digits: Substring, in text: String) -> Bool {
    !isDecimalFraction(before: digits.startIndex, in: text)
      && !isColonClockMinutes(digits: digits, before: digits.startIndex, in: text)
  }

  private static func minutes(_ count: Substring, _ extraMinutes: Substring?, unit: DurationUnit) -> Int? {
    guard let count = Int(count) else { return nil }
    let (scaled, overflow) = count.multipliedReportingOverflow(by: unit.minutesPerUnit)
    guard !overflow else { return nil }
    guard let extraMinutes else { return scaled }
    guard let extra = Int(extraMinutes) else { return nil }
    let (total, sumOverflow) = scaled.addingReportingOverflow(extra)
    return sumOverflow ? nil : total
  }

  /// `.` or `,` then a digit, or `:` then a minute value, after the bare number.
  private static func isFollowedByFractionOrClockMinutes(_ end: String.Index, in text: String) -> Bool {
    let scalars = text.unicodeScalars
    guard end < scalars.endIndex else { return false }
    let separator = scalars[end]
    let rest = scalars[scalars.index(after: end)...]
    switch separator {
    case ".", ",":
      return rest.first.map(isASCIIDigit) ?? false
    case ":":
      let minute = Array(rest.prefix(clockPartDigits))
      return minute.count == clockPartDigits
        && minute[0].value >= asciiZero && minute[0].value <= asciiFive
        && isASCIIDigit(minute[1])
    default:
      return false
    }
  }
}
