import Foundation
import MC1Services
import UIKit

extension ChatMessageBakeState {
  // MARK: - Preview Rehydration

  /// Synchronously detects the message's first URL and rehydrates preview /
  /// inline-image state from the process-lifetime decoded caches, before the
  /// build inputs are snapshotted. A fresh bake state (every open, prewarm,
  /// or refresh) starts with cold dictionaries while the shared coordinator's
  /// items are already on screen; without this seed the first rebuild bakes
  /// the link-preview fragment as `.idle` (a zero-height `EmptyView`) and the
  /// list visibly reflows when async detection later restores the card.
  /// Idempotent: the `cachedURLs` sentinel (present-but-nil marks "detected,
  /// no URL") makes repeat calls a dictionary hit.
  func seedPreviewStateIfNeeded(for message: MessageDTO, envInputs: EnvInputs) {
    guard cachedURLs[message.id] == nil else { return }
    let url = LinkPreviewService.extractFirstURL(from: message.text)
    cachedURLs[message.id] = url
    rehydrateInlineImageStateIfCached(messageID: message.id, url: url, envInputs: envInputs)
    rehydratePreviewStateIfCached(messageID: message.id, url: url)
  }

  /// Seed `decodedImages` / `imageIsGIF` / `previewStates = .loaded`
  /// atomically when the singleton has a decoded image for this URL.
  /// Also restores raw bytes into `loadedImageData` for static images so
  /// the full-screen viewer and share sheet (which need original
  /// resolution and `Data`) keep working post-rehydration. Idempotent
  /// and a no-op for non-image URLs, the master toggle being off, or a
  /// per-bake state that has already advanced past a tap-to-load-eligible
  /// state. Master-gated only, no scope check: this reads the decoded cache
  /// and performs no network fetch, so a cached image beats the tap-to-load
  /// placeholder under scope-off too, matching `LinkPreviewCache.preview`'s
  /// cache-before-gate ordering for cards.
  private func rehydrateInlineImageStateIfCached(
    messageID: UUID,
    url: URL?,
    envInputs: EnvInputs
  ) {
    guard envInputs.previewsEnabled,
          let url,
          ImageURLClassifier.isImageURL(url) else { return }
    let existingState = previewStates[messageID]
    guard existingState == nil || existingState == .idle || existingState == .disabled else { return }
    let directURL = ImageURLClassifier.directImageURL(for: url)
    guard let cached = InlineImageCache.shared.decoded(for: directURL) else { return }
    applyDecodedImage(cached, for: messageID)
  }

  /// Seed `loadedPreviews` / `decodedPreviewAssets` / `previewStates = .loaded`
  /// atomically when `DecodedPreviewCache` already holds a decoded card for
  /// this URL. Painting `.loaded` in the same call frame as URL detection
  /// means the bubble skips the loading shimmer on chat re-entry. Idempotent
  /// and a no-op once state has advanced past `.idle`; image URLs are handled
  /// by `rehydrateInlineImageStateIfCached` and have no preview entry here.
  private func rehydratePreviewStateIfCached(messageID: UUID, url: URL?) {
    guard let url else { return }
    let existingState = previewStates[messageID]
    guard existingState == nil || existingState == .idle else { return }
    guard let cached = DecodedPreviewCache.shared.decoded(for: url) else { return }
    loadedPreviews[messageID] = cached.dto
    decodedPreviewAssets[messageID] = DecodedPreviewAssets(image: cached.hero, icon: cached.icon)
    previewStates[messageID] = .loaded
  }

  /// Atomically seeds the per-bake image state from a decoded cache entry.
  /// Restores `loadedImageData` from the entry's raw bytes when available
  /// so the full-screen viewer and share sheet keep working after
  /// rehydration. Does not rebuild the render item; the caller owns that
  /// step.
  func applyDecodedImage(_ cached: CachedDecodedImage, for messageID: UUID) {
    decodedImages[messageID] = cached.image
    imageIsGIF[messageID] = cached.isGIF
    if let data = cached.data {
      loadedImageData.setObject(data as NSData, forKey: messageID as NSUUID, cost: data.count)
    }
    previewStates[messageID] = .loaded
  }
}
