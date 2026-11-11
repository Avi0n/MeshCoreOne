import MC1Services
import SwiftUI

/// Shared MECP presentation outside the bubble body: the details-sheet line
/// and the VoiceOver reading. The bubble always draws the reading.
enum MECPPresentation {
  /// `MECP: <stored text>` when `storedText` decodes. Nil otherwise.
  static func detailLine(storedText: String) -> String? {
    guard MECPDecode.decode(storedText) != nil else { return nil }
    return L10n.Chats.Chats.Message.Info.mecp(storedText)
  }

  static func spokenReading(_ reading: String) -> String {
    L10n.Chats.Chats.Message.readingAccessibility(
      reading.replacingOccurrences(of: "\n", with: " ")
    )
  }

  @ViewBuilder
  static func detailRow(storedText: String) -> some View {
    if let line = detailLine(storedText: storedText) {
      ActionInfoRow(text: line)
    }
  }
}
