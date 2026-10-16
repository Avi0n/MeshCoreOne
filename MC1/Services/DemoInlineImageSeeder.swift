import Foundation
import MC1Services
import UIKit

/// Pre-seeds the process-wide ``InlineImageCache`` with demo mode's inline image.
/// The render path shows `.loaded` once the cache holds a decoded image for the URL,
/// so seeding it here (the cache and `UIImage` are app-layer) lets the package-level
/// seed reference the URL while the pixels stay embedded and render offline.
enum DemoInlineImageSeeder {
  /// Idempotent: re-seeding overwrites the same cache key.
  static func seed() {
    guard let url = URL(string: MockDataProvider.inlineImageURL),
          let image = UIImage(data: MockDataProvider.demoImageData) else { return }
    let entry = CachedDecodedImage(image: image, isGIF: false, data: MockDataProvider.demoImageData)
    InlineImageCache.shared.storeDecoded(entry, for: ImageURLClassifier.directImageURL(for: url))
  }
}
