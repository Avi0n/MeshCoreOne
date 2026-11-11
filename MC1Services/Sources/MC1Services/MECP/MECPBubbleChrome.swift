import Foundation

/// Reading drawn when `MECPDecode` accepts the stored string.
public enum MECPBubbleChrome: Sendable, Hashable {
  /// Newline-joined reading. Each part is one line in the bubble.
  case reading(String)

  public static func resolve(text: String) -> MECPBubbleChrome? {
    guard MECPDecode.decode(text) != nil else { return nil }
    return .reading(MECPCatalog.reading(text))
  }
}
