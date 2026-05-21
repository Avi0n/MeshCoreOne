import CoreGraphics
import Foundation
import ImageIO
import MC1Services
import OSLog

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

    private static let maxEntryCount = 50
    private static let maxTotalCostBytes = 50 * 1024 * 1024 // 50MB
    private static let maxDownloadBytes = 10 * 1024 * 1024  // 10MB per image
    private static let probeMaxBufferBytes = 1 * 1024 * 1024 // 1MB cap for probe bodies
    private static let probeByteRange = "bytes=0-65535"
    private static let rangeHeaderField = "Range"
    private static let httpStatusOK = 200
    private static let httpStatusPartialContent = 206

    init() {
        memoryCache.countLimit = Self.maxEntryCount
        memoryCache.totalCostLimit = Self.maxTotalCostBytes
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
