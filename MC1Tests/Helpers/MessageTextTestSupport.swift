@testable import MC1
import SwiftUI

@MainActor
enum MessageTextTestSupport {
  static func formattedText(_ text: String) -> AttributedString {
    let theme = Theme.default
    return MessageText.buildFormattedText(
      text: text,
      isOutgoing: false,
      currentUserName: nil,
      isHighContrast: false,
      outgoingTextColor: theme.outgoingTextColor,
      hashtagColor: theme.hashtagColor,
      identityGamut: theme.identityGamut,
      identityBackgroundLuminances: theme.avatarSurfaceLuminances(
        colorScheme: .light,
        contrast: .standard
      )
    ).text
  }
}
