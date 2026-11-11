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

  public init(severity: Severity, codes: [String], freetext: String?) {
    self.severity = severity
    self.codes = codes
    self.freetext = freetext
  }

  public var personCount: Int? {
    MECPDecode.extracted(from: freetext, codes: codes).personCount
  }

  public var timestampHHMM: String? {
    MECPDecode.extracted(from: freetext, codes: codes).timestampHHMM
  }

  public var eta: Int? {
    MECPDecode.extracted(from: freetext, codes: codes).eta
  }

  public var displayTail: String? {
    MECPDecode.extracted(from: freetext, codes: codes).displayTail
  }
}
