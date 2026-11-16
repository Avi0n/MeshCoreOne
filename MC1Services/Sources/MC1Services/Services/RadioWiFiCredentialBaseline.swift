import Foundation

/// A known credential pair from one editor, service, and stable mutation revision.
public struct RadioWiFiCredentialBaseline: Sendable {
  public static let minSSIDBytes = 1
  public static let maxSSIDBytes = 32
  public static let maxPasswordBytes = 63
  private static let nullByte: UInt8 = 0
  public let contextID: UUID
  public let radioID: UUID
  public let editorID: UUID
  public let revision: UInt64
  public let ssid: String
  public let password: String
  let readID: UUID

  public static func isRepresentable(_ text: String) -> Bool {
    !text.utf8.contains(nullByte)
  }

  public static func hasSameBytes(_ first: String, _ second: String) -> Bool {
    first.utf8.elementsEqual(second.utf8)
  }

  public static func validCredentials(ssid: String, password: String) -> Bool {
    (minSSIDBytes...maxSSIDBytes).contains(ssid.utf8.count) && password.utf8.count <= maxPasswordBytes
      && isRepresentable(ssid) && isRepresentable(password)
  }

  func updating(ssid: String, password: String, revision: UInt64) -> Self {
    Self(
      contextID: contextID,
      radioID: radioID,
      editorID: editorID,
      revision: revision,
      ssid: ssid,
      password: password,
      readID: readID
    )
  }
}
