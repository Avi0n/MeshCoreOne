import Foundation

/// Result of running language detection for one message.
/// Missing dictionary key means detection has not run yet.
public enum DetectedLanguage: Sendable, Equatable {
  case undetermined
  case identified(languageCode: String)

  public var code: String? {
    switch self {
    case .undetermined: nil
    case let .identified(languageCode): languageCode
    }
  }
}
