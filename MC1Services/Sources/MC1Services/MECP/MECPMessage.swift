import Foundation

/// Sender-shaped MECP value. Person count, an accepted clock, and an R03 eta are derived from the tail.
public struct MECPMessage: Sendable, Equatable {
  public enum Severity: Int, Sendable, Equatable {
    case mayday = 0
    case urgent = 1
    case safety = 2
    case routine = 3
  }

  /// UTF-8 budget a later sender matches. Decode does not reject a longer string.
  public static let maxUTF8Bytes = 200

  public let severity: Severity
  public let codes: [String]
  public let freetext: String?
  private let extraction: MECPDecode.Extraction

  public init(severity: Severity, codes: [String], freetext: String?) {
    self.severity = severity
    self.codes = codes
    self.freetext = freetext
    extraction = MECPDecode.extracted(from: freetext, codes: codes)
  }

  public static func == (lhs: Self, rhs: Self) -> Bool {
    lhs.severity == rhs.severity && lhs.codes == rhs.codes && lhs.freetext == rhs.freetext
  }

  public var personCount: Int? {
    extraction.personCount
  }

  public var timestampHHMM: String? {
    extraction.timestampHHMM
  }

  public var eta: Int? {
    extraction.eta
  }

  public var displayTail: String? {
    extraction.displayTail
  }
}
