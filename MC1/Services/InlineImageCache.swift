import CoreGraphics
import Foundation
import ImageIO
import MC1Services
import os
import OSLog
import UIKit

/// Result of an inline image fetch
enum InlineImageResult: Sendable {
    case loaded(Data)
    case loading
    case failed
}

/// Actor-based cache for fetching and caching inline image data.
/// Uses NSCache for memory management and AsyncSemaphore for concurrency limiting.
actor InlineImageCache {
    static let shared = InlineImageCache()

    private let logger = Logger(subsystem: "com.mc1", category: "InlineImageCache")

    private let memoryCache = NSCache<NSString, CachedImageData>()
    private let fetchSemaphore = AsyncSemaphore(value: 3)
    private var failedURLs: Set<String> = []
    private var inFlightURLs: Set<String> = []
    private var dimensionsStore: InlineImageDimensionsStore?

    /// Per-URL decoded-image cache. Survives `ChatViewModel` teardown so
    /// exit-then-reenter of the same chat does not reshimmer. Read via a
    /// wait-free `OSAllocatedUnfairLock` mirror so main-actor view bodies
    /// can resolve cache hits without awaiting an actor hop. FIFO eviction
    /// bounded by `maxDecodedEntryCount` and `maxDecodedTotalCostBytes`.
    private let decodedMirror = OSAllocatedUnfairLock<DecodedCacheState>(initialState: DecodedCacheState())

    private static let maxEntryCount = 50
    private static let maxTotalCostBytes = 50 * 1024 * 1024 // 50MB
    private static let maxDownloadBytes = 10 * 1024 * 1024  // 10MB per image
    private static let probeMaxBufferBytes = 1 * 1024 * 1024 // 1MB cap for probe bodies
    private static let probeByteRange = "bytes=0-65535"
    private static let rangeHeaderField = "Range"
    private static let httpStatusOK = 200
    private static let httpStatusPartialContent = 206

    /// Decoded-cache caps cover the total working-set per entry: decoded
    /// pixel bytes (cgImage bytesPerRow times height across frames) plus
    /// optional retained encoded bytes for the viewer/share path. A 4MB
    /// JPEG decoded to a 4096x3072 RGBA bitmap is ~48MB of pixels alone;
    /// the encoded `data` term keeps the cost honest when both are held.
    private static let maxDecodedEntryCount = 50
    private static let maxDecodedTotalCostBytes = 100 * 1024 * 1024

    init() {
        memoryCache.countLimit = Self.maxEntryCount
        memoryCache.totalCostLimit = Self.maxTotalCostBytes

        // Mirror NSCache's auto-eviction-on-memory-warning behavior for the
        // hand-rolled decoded mirror. The closure is retained by
        // NotificationCenter for the singleton's lifetime; weak self keeps
        // the contract clean even though the cache outlives the process.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            self?.clearDecodedMirror()
        }
    }

    /// Empties the decoded-image mirror in response to system memory
    /// pressure. `nonisolated` so the notification block can call it
    /// directly without an actor hop on whichever queue the system
    /// delivers the warning.
    nonisolated func clearDecodedMirror() {
        decodedMirror.withLock { state in
            state.entries.removeAll()
            state.insertionOrder.removeAll()
            state.totalCostBytes = 0
        }
    }

    /// Registers the persistence sink for successful probes. Held strongly;
    /// later calls overwrite the reference so each connection's store wins.
    func attachDimensionsStore(_ store: InlineImageDimensionsStore) {
        self.dimensionsStore = store
    }

    /// Fetches image data for the given URL, returning cached data when available.
    func fetchImageData(for url: URL) async -> InlineImageResult {
        let key = url.absoluteString

        // Check negative cache
        if failedURLs.contains(key) {
            return .failed
        }

        // Check memory cache
        if let cached = memoryCache.object(forKey: key as NSString) {
            return .loaded(cached.data)
        }

        // Prevent duplicate in-flight fetches
        guard !inFlightURLs.contains(key) else {
            return .loading
        }

        inFlightURLs.insert(key)
        await fetchSemaphore.wait()

        // Single exit path after semaphore acquisition
        let result: InlineImageResult
        if let cached = memoryCache.object(forKey: key as NSString) {
            result = .loaded(cached.data)
        } else if Task.isCancelled {
            result = .failed
        } else {
            result = await performFetch(for: url, key: key)
        }

        await fetchSemaphore.signal()
        inFlightURLs.remove(key)
        return result
    }

    /// Removes a URL from the negative cache, allowing it to be retried.
    func clearFailure(for url: URL) {
        failedURLs.remove(url.absoluteString)
    }

    /// Persists a decoded `UIImage` keyed on the direct image URL so a
    /// later chat re-entry can skip the decode step. Called from
    /// `ChatViewModel.fetchInlineImage` *before* its per-VM cancellation
    /// guards so a scroll-away or chat-exit mid-decode still hands the
    /// pixels to the next visit. FIFO eviction inside the lock keeps the
    /// mirror within budget. `nonisolated` because the body only touches
    /// the lock-guarded mirror, never actor-isolated state.
    nonisolated func storeDecoded(_ entry: CachedDecodedImage, for url: URL) {
        let key = url.absoluteString
        decodedMirror.withLock { state in
            if let existing = state.entries[key] {
                state.totalCostBytes -= existing.cost
                if let idx = state.insertionOrder.firstIndex(of: key) {
                    state.insertionOrder.remove(at: idx)
                }
            }
            state.entries[key] = entry
            state.insertionOrder.append(key)
            state.totalCostBytes += entry.cost

            // Keep at least the just-inserted entry, even when it singly
            // exceeds the cost budget — a 4K image decodes to ~50MB on its
            // own and we'd otherwise evict immediately and never serve it.
            while state.insertionOrder.count > 1,
                  state.insertionOrder.count > Self.maxDecodedEntryCount
                    || state.totalCostBytes > Self.maxDecodedTotalCostBytes {
                let oldest = state.insertionOrder.removeFirst()
                if let evicted = state.entries.removeValue(forKey: oldest) {
                    state.totalCostBytes -= evicted.cost
                }
            }
        }
    }

    /// Nonisolated wait-free decoded-image lookup. Safe to call from a
    /// SwiftUI view body or the main-actor URL-detection write path
    /// without an actor hop, mirroring the shape of
    /// `InlineImageDimensionsStore.aspect(for:)`.
    nonisolated func decoded(for url: URL) -> CachedDecodedImage? {
        decodedMirror.withLock { $0.entries[url.absoluteString] }
    }

    /// Probes the image header for pixel dimensions without persisting the bytes.
    /// Uses a small Range request and `ImageHeaderDecoder`. Persists the resolved
    /// size to the attached dimensions store on success. Failures do not touch
    /// the negative cache or the in-flight set.
    func probeImageDimensions(url: URL) async -> CGSize? {
        guard await URLSafetyChecker.isSafe(url) else {
            logger.debug("Blocked probe to unsafe URL: \(url.host() ?? "unknown")")
            return nil
        }

        logger.debug("Probing image dimensions: \(url.absoluteString)")

        await fetchSemaphore.wait()

        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue(Self.probeByteRange, forHTTPHeaderField: Self.rangeHeaderField)

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            logger.info("Image probe network failure: \(error.localizedDescription)")
            await fetchSemaphore.signal()
            return nil
        }

        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == Self.httpStatusOK
                || httpResponse.statusCode == Self.httpStatusPartialContent else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            logger.info("Image probe non-success status \(code): \(url.absoluteString)")
            await fetchSemaphore.signal()
            return nil
        }

        guard data.count <= Self.probeMaxBufferBytes else {
            logger.info("Image probe body exceeds cap: \(url.absoluteString)")
            await fetchSemaphore.signal()
            return nil
        }

        guard let dims = ImageHeaderDecoder.decodeDimensions(from: data) else {
            logger.info("Image probe could not decode dimensions: \(url.absoluteString)")
            await fetchSemaphore.signal()
            return nil
        }

        let size = CGSize(width: dims.width, height: dims.height)
        logger.debug("Probed dimensions \(dims.width)x\(dims.height): \(url.absoluteString)")

        if let dimensionsStore {
            await dimensionsStore.save(url: url, size: size)
        }

        await fetchSemaphore.signal()
        return size
    }

    /// Performs the HTTP fetch, validates the response, and caches the result.
    private func performFetch(for url: URL, key: String) async -> InlineImageResult {
        guard await URLSafetyChecker.isSafe(url) else {
            logger.debug("Blocked fetch to unsafe URL: \(url.host() ?? "unknown")")
            failedURLs.insert(key)
            return .failed
        }

        do {
            let (data, response) = try await URLSession.shared.data(from: url)

            guard let httpResponse = response as? HTTPURLResponse,
                  (200...299).contains(httpResponse.statusCode) else {
                logger.debug("Non-success HTTP status for image: \(url.absoluteString)")
                failedURLs.insert(key)
                return .failed
            }

            guard data.count <= Self.maxDownloadBytes else {
                logger.debug("Image too large (\(data.count) bytes): \(url.absoluteString)")
                failedURLs.insert(key)
                return .failed
            }

            // Lightweight validation: check that ImageIO recognizes the data as an image
            guard CGImageSourceCreateWithData(data as CFData, nil) != nil else {
                logger.debug("Data is not a valid image: \(url.absoluteString)")
                failedURLs.insert(key)
                return .failed
            }

            memoryCache.setObject(CachedImageData(data), forKey: key as NSString, cost: data.count)
            return .loaded(data)

        } catch {
            if !Task.isCancelled {
                logger.debug("Failed to fetch image: \(error.localizedDescription)")
                failedURLs.insert(key)
            }
            return .failed
        }
    }

}

/// Wrapper class for NSCache (requires reference type)
private final class CachedImageData: @unchecked Sendable {
    let data: Data
    init(_ data: Data) { self.data = data }
}

/// Reference-typed payload for the decoded-image cache. Carries optional
/// raw bytes so the full-screen viewer (which needs original-resolution
/// pixels and the share-sheet `Data`) keeps working after a chat re-entry
/// repopulates state from the singleton. `@unchecked Sendable` is sound
/// because the stored properties are `let` and `UIImage` / `Data` are
/// immutable post-construction.
final class CachedDecodedImage: @unchecked Sendable {
    let image: UIImage
    let isGIF: Bool
    /// Original encoded bytes for static images, so the full-screen viewer
    /// can present at full resolution and the share sheet can hand off
    /// raw `Data`. Nil for GIFs, matching the existing per-VM
    /// `loadedImageData` policy (GIFs inline-animate via the UIImage but
    /// do not open the full-screen viewer).
    let data: Data?
    let cost: Int

    init(image: UIImage, isGIF: Bool, data: Data?) {
        self.image = image
        self.isGIF = isGIF
        self.data = data
        self.cost = Self.computeCost(for: image, isGIF: isGIF) + (data?.count ?? 0)
    }

    private static func computeCost(for image: UIImage, isGIF: Bool) -> Int {
        if isGIF, let frames = image.images, !frames.isEmpty {
            return frames.reduce(0) { $0 + ImageByteCost.bytes(for: $1) }
        }
        return ImageByteCost.bytes(for: image)
    }
}

/// State held under the decoded-mirror lock. Combining the dict with the
/// insertion-order list and running cost lets a single `withLock` perform
/// both the write and the eviction sweep atomically.
private struct DecodedCacheState {
    var entries: [String: CachedDecodedImage] = [:]
    var insertionOrder: [String] = []
    var totalCostBytes: Int = 0
}
