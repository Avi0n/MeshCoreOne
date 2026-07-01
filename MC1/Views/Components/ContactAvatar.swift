import MC1Services
import SwiftUI

struct ContactAvatar: View {
  @Environment(\.appTheme) private var theme
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  let name: String
  let size: CGFloat

  init(contact: ContactDTO, size: CGFloat) {
    name = contact.displayName
    self.size = size
  }

  init(name: String, size: CGFloat) {
    self.name = name
    self.size = size
  }

  var body: some View {
    Text(initials)
      .font(.system(size: size * 0.4, weight: .semibold))
      .foregroundStyle(glyphColor)
      .frame(width: size, height: size)
      .background(avatarColor, in: .circle)
  }

  private var initials: String {
    if let emoji = name.first(where: \.isEmoji) {
      return String(emoji)
    }
    let words = name.split(separator: " ")
    if words.count >= 2 {
      return String(words[0].prefix(1) + words[1].prefix(1)).uppercased()
    }
    return String(name.prefix(1)).uppercased()
  }

  private var avatarColor: Color {
    theme.identityColor(forName: name, colorScheme: colorScheme, contrast: colorSchemeContrast)
  }

  private var glyphColor: Color {
    theme.avatarGlyphColor(
      forFill: avatarColor,
      usesCategoryOverride: false,
      colorScheme: colorScheme,
      contrast: colorSchemeContrast
    )
  }
}
