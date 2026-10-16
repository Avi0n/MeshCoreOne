import Foundation

/// Single source of truth for the `meshcoreone://mention/<name>` deep link that
/// `MessageText` produces and `ChatConversationView` consumes. Centralising the
/// scheme, host, and percent-encoding here keeps the producer and consumer from
/// diverging — a mismatch would route a mention tap to the wrong contact.
enum MentionDeeplinkSupport {
  static let scheme = "meshcoreone"
  static let host = "mention"

  /// `/` is removed from the allowed set so a name containing a slash is
  /// percent-encoded into a single path component instead of splitting the
  /// URL path (which would drop everything before the last slash).
  private static let nameAllowed = CharacterSet.urlPathAllowed
    .subtracting(CharacterSet(charactersIn: "/"))

  /// Builds `meshcoreone://mention/<percent-encoded-name>` for a mention run,
  /// or `nil` if the name cannot be encoded or is empty.
  static func url(forName name: String) -> URL? {
    guard let encoded = name.addingPercentEncoding(withAllowedCharacters: nameAllowed),
          !encoded.isEmpty else {
      return nil
    }
    return URL(string: "\(scheme)://\(host)/\(encoded)")
  }

  /// Returns the decoded mention name if `url` is a mention deep link, else
  /// `nil`. Reads the still-encoded path and decodes it exactly once;
  /// `lastPathComponent` is avoided because it decodes `%2F` back to `/` and
  /// then re-splits, dropping everything before the slash.
  static func name(from url: URL) -> String? {
    guard url.scheme == scheme, url.host == host else { return nil }
    var encodedPath = url.path(percentEncoded: true)
    if encodedPath.hasPrefix("/") {
      encodedPath.removeFirst()
    }
    guard !encodedPath.isEmpty,
          let decoded = encodedPath.removingPercentEncoding,
          !decoded.isEmpty else {
      return nil
    }
    return decoded
  }
}
