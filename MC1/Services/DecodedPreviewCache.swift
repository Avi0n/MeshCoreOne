import Foundation
import MC1Services
import os
import UIKit

/// Reference-typed payload for the decoded link-preview cache. Carries the
/// DTO metadata (title, dimensions) alongside the decoded hero and icon so a
/// chat re-entry can repaint a loaded card without an actor hop or a
/// re-decode. `@unchecked Sendable` is sound because every stored property is
/// `let` and `UIImage` / `LinkPreviewDataDTO` are immutable post-construction.
final class CachedDecodedPreview: @unchecked Sendable {
    private static let bytesPerPixelRGBA = 4

    let dto: LinkPreviewDataDTO
    let hero: UIImage?
    let icon: UIImage?
    let cost: Int

    init(dto: LinkPreviewDataDTO, hero: UIImage?, icon: UIImage?) {
        // Strip the raw image/icon bytes: the decoded hero and icon carry the
        // pixels, and the rehydration render path reads only the DTO's url,
        // title, and dimensions. Retaining the compressed source bytes would
        // be dead weight that the pixel-based cost budget never accounts for.
        self.dto = LinkPreviewDataDTO(
            url: dto.url,
            title: dto.title,
            imageWidth: dto.imageWidth,
            imageHeight: dto.imageHeight,
            fetchedAt: dto.fetchedAt
        )
        self.hero = hero
        self.icon = icon
        self.cost = Self.pixelCost(hero) + Self.pixelCost(icon)
    }

    private static func pixelCost(_ image: UIImage?) -> Int {
        guard let image else { return 0 }
        if let cgImage = image.cgImage {
            return cgImage.bytesPerRow * cgImage.height
        }
        return Int(image.size.width * image.size.height) * bytesPerPixelRGBA
    }
}

/// Process-lifetime cache of decoded link-preview assets keyed by URL.
/// Survives `ChatViewModel` teardown so exiting and re-entering the same chat
/// repaints loaded cards without reshimmering or re-decoding. The raw preview
/// bytes already survive via `LinkPreviewCache` (NSCache + SwiftData); this
/// mirror closes the remaining gap — the decoded `UIImage`s and the `.loaded`
/// render state — exactly as `InlineImageCache`'s decoded mirror does for
/// inline images. Reads are wait-free through an `OSAllocatedUnfairLock` so a
/// main-actor URL-detection write path can resolve a hit and paint `.loaded`
/// in the same call frame. FIFO eviction bounded by `maxEntryCount` and
/// `maxTotalCostBytes`; auto-clears on memory pressure.
final class DecodedPreviewCache: Sendable {
    static let shared = DecodedPreviewCache()

    private static let maxEntryCount = 50
    private static let maxTotalCostBytes = 50 * 1024 * 1024 // 50MB

    private let mirror = OSAllocatedUnfairLock<DecodedPreviewCacheState>(initialState: DecodedPreviewCacheState())

    init() {
        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            self?.clear()
        }
    }

    /// Persists a decoded preview keyed on the message's detected URL. Called
    /// from `ChatViewModel.decodeAndStorePreviewImages` once the hero and icon
    /// decode completes, before the per-VM apply, so a chat-exit mid-decode
    /// still hands the assets to the next visit. FIFO eviction inside the lock
    /// keeps the mirror within budget.
    func store(_ entry: CachedDecodedPreview, for url: URL) {
        let key = url.absoluteString
        mirror.withLock { state in
            if let existing = state.entries[key] {
                state.totalCostBytes -= existing.cost
                if let idx = state.insertionOrder.firstIndex(of: key) {
                    state.insertionOrder.remove(at: idx)
                }
            }
            state.entries[key] = entry
            state.insertionOrder.append(key)
            state.totalCostBytes += entry.cost

            // Keep at least the just-inserted entry even when it singly
            // exceeds the cost budget, so a large hero is still served once.
            while state.insertionOrder.count > 1,
                  state.insertionOrder.count > Self.maxEntryCount
                    || state.totalCostBytes > Self.maxTotalCostBytes {
                let oldest = state.insertionOrder.removeFirst()
                if let evicted = state.entries.removeValue(forKey: oldest) {
                    state.totalCostBytes -= evicted.cost
                }
            }
        }
    }

    /// Wait-free decoded-preview lookup. Safe to call from a main-actor view
    /// body or the URL-detection write path without an await.
    func decoded(for url: URL) -> CachedDecodedPreview? {
        mirror.withLock { $0.entries[url.absoluteString] }
    }

    /// Empties the cache in response to system memory pressure.
    func clear() {
        mirror.withLock { state in
            state.entries.removeAll()
            state.insertionOrder.removeAll()
            state.totalCostBytes = 0
        }
    }
}

/// State held under the mirror lock. Combining the dict with the
/// insertion-order list and running cost lets a single `withLock` perform
/// both the write and the eviction sweep atomically.
private struct DecodedPreviewCacheState {
    var entries: [String: CachedDecodedPreview] = [:]
    var insertionOrder: [String] = []
    var totalCostBytes: Int = 0
}
