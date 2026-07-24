import MC1Services
import SwiftUI
import UIKit

/// Process-lifetime cache of decoded avatar images, keyed by the raw JPEG data.
/// Avoids re-running `UIImage(data:)` on every cell redraw while scrolling a contact list.
private enum AvatarImageCache {
  /// NSCache is internally thread-safe; its lack of Sendable conformance is a
  /// missing annotation in Foundation, not an actual data race risk here.
  nonisolated(unsafe) static let shared = NSCache<NSData, UIImage>()
}

struct ContactAvatar: View {
  @Environment(\.appTheme) private var theme
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  let name: String
  let size: CGFloat
  let imageData: Data?

  init(contact: ContactDTO, size: CGFloat) {
    name = contact.displayName
    self.size = size
    imageData = contact.avatarImageData
  }

  init(name: String, size: CGFloat) {
    self.name = name
    self.size = size
    imageData = nil
  }

  var body: some View {
    if let imageData, let uiImage = cachedImage(for: imageData) {
      Image(uiImage: uiImage)
        .resizable()
        .scaledToFill()
        .frame(width: size, height: size)
        .clipShape(.circle)
    } else {
      Text(initials)
        .font(.system(size: size * 0.4, weight: .semibold))
        .foregroundStyle(glyphColor)
        .frame(width: size, height: size)
        .background(avatarColor, in: .circle)
    }
  }

  private func cachedImage(for data: Data) -> UIImage? {
    let key = data as NSData
    if let cached = AvatarImageCache.shared.object(forKey: key) {
      return cached
    }
    guard let decoded = UIImage(data: data) else { return nil }
    AvatarImageCache.shared.setObject(decoded, forKey: key, cost: data.count)
    return decoded
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
