import MC1Services
import SwiftUI

/// The body a bubble draws and speaks for one stored string.
/// A reading replaces the stored text and hides Translation chrome.
/// A showing translation replaces the stored text and keeps its chrome.
struct MessageVisibleBody: Equatable {
  /// Plain body. Nil means draw the formatted stored text.
  let plainText: String?
  /// Chrome to show. Nil for a reading, and when none was resolved.
  let translation: MessageTranslationChrome?
  let spokenText: String

  init(stored: String, translation: MessageTranslationChrome?) {
    if let reading = MECPCatalog.reading(stored) {
      plainText = reading
      self.translation = nil
      spokenText = L10n.Chats.Chats.Message.readingAccessibility(
        reading.replacingOccurrences(of: "\n", with: " ")
      )
    } else {
      self.translation = translation
      switch translation?.phase {
      case let .showing(translated, _):
        plainText = translated
        spokenText = L10n.Chats.Chats.Message.translatedAccessibility(translated)
      case .inProgress:
        plainText = nil
        spokenText = "\(stored), \(L10n.Chats.Chats.Message.Action.translating)"
      case .offer, nil:
        plainText = nil
        spokenText = stored
      }
    }
  }

  /// `MECP: <stored text>` when `storedText` decodes. Nil otherwise.
  static func detailLine(storedText: String) -> String? {
    guard MECPCatalog.reading(storedText) != nil else { return nil }
    return L10n.Chats.Chats.Message.Info.mecp(storedText)
  }

  @ViewBuilder
  static func detailRow(storedText: String) -> some View {
    if let line = detailLine(storedText: storedText) {
      ActionInfoRow(text: line)
    }
  }
}
