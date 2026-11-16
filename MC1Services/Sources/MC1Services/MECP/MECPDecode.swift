/// The MECP wire grammar. A string that does not match returns nil.
public enum MECPDecode {
  public static func decode(_ input: String) -> MECPMessage? {
    guard input.hasPrefix(prefix) else { return nil }
    let severityIndex = input.index(input.startIndex, offsetBy: prefix.count, limitedBy: input.endIndex)
    guard let severityIndex, severityIndex < input.endIndex else { return nil }
    guard isASCIIDigit(input[severityIndex]),
          let severityDigit = input[severityIndex].wholeNumberValue,
          let severity = MECPMessage.Severity(rawValue: severityDigit) else {
      return nil
    }

    let bodyOffset = prefix.count + bodyLead
    let body = if let bodyIndex = input.index(input.startIndex, offsetBy: bodyOffset, limitedBy: input.endIndex), bodyIndex < input.endIndex {
      String(input[bodyIndex...])
    } else {
      ""
    }

    var codes: [String] = []
    var tailTokens: [String] = []
    var readingTail = false
    for token in body.split(separator: " ", omittingEmptySubsequences: true) {
      let text = String(token)
      if !readingTail, isCodeToken(text) {
        codes.append(text)
      } else {
        readingTail = true
        tailTokens.append(text)
      }
    }

    let freetext = tailTokens.isEmpty ? nil : tailTokens.joined(separator: " ")
    return MECPMessage(severity: severity, codes: codes, freetext: freetext)
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

    if let match = firstPax(in: freetext) {
      extraction.personCount = match.count
      removal.append(match.range)
    }

    if let match = firstTimestamp(in: freetext), isAcceptedClock(match.digits) {
      extraction.timestampHHMM = match.digits
      removal.append(match.range)
    }

    if codes.contains(etaCode), let match = leadingEta(in: freetext) {
      extraction.eta = match.minutes
      removal.append(match.range)
    }

    extraction.displayTail = removing(removal, from: freetext)
    return extraction
  }

  private static let prefix = "MECP/"
  private static let bodyLead = 2
  private static let etaCode = "R03"
  private static let maxHour = 23
  private static let maxMinute = 59
  private static let codeLength = 3
  private static let clockDigits = 4
  private static let paxSuffix = "pax"

  private static func isASCIIDigit(_ character: Character) -> Bool {
    character.isASCII && character.isNumber
  }

  private static func isCodeToken(_ token: String) -> Bool {
    guard token.count == codeLength else { return false }
    var characters = token.makeIterator()
    guard let letter = characters.next(), letter.isASCII, letter.isUppercase, letter.isLetter,
          let tens = characters.next(), isASCIIDigit(tens),
          let ones = characters.next(), isASCIIDigit(ones) else {
      return false
    }
    return true
  }

  private static func firstPax(in text: String) -> (count: Int, range: Range<String.Index>)? {
    var index = text.startIndex
    while index < text.endIndex {
      guard isASCIIDigit(text[index]) else {
        index = text.index(after: index)
        continue
      }
      let start = index
      while index < text.endIndex, isASCIIDigit(text[index]) {
        index = text.index(after: index)
      }
      guard text[index...].hasPrefix(paxSuffix) else { continue }
      guard let count = Int(text[start..<index]) else { return nil }
      let end = text.index(index, offsetBy: paxSuffix.count)
      return (count, start..<end)
    }
    return nil
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
