import Foundation
import OSLog
import MC1Services

// MARK: - Constants

private enum CacheConfig {
    static let maxEntryCount = 100
    static let maxTotalCostBytes = 50 * 1024 * 1024  // 50MB
    static let maxConcurrentFetches = 3
}

/// Two-tier cache for link previews with URL-based deduplication.
/// Uses actor isolation for thread safety without blocking the main thread.
/// Limits concurrent LPMetadataProvider instances to prevent WKWebView spawn bursts.
actor LinkPreviewCache: LinkPreviewCaching {
    private let logger = Logger(subsystem: "com.mc1", category: "LinkPreviewCache")
    private let memoryCache = NSCache<NSString, CachedPreview>()
    private let service = LinkPreviewService()
    private nonisolated let preferences = LinkPreviewPreferences()

    /// URLs currently being fetched (prevents duplicate in-flight requests)
    private var inFlightFetches: Set<String> = []

    /// URLs that have been fetched but have no preview available
    private var noPreviewAvailable: Set<String> = []

    /// Semaphore to limit concurrent LPMetadataProvider instances.
    /// Each LPMetadataProvider spawns WKWebView on main thread.
    private let fetchSemaphore = AsyncSemaphore(value: CacheConfig.maxConcurrentFetches)

    init() {
        memoryCache.countLimit = CacheConfig.maxEntryCount
        memoryCache.totalCostLimit = CacheConfig.maxTotalCostBytes
    }

    func preview(
        for url: URL,
        using dataStore: any PersistenceStoreProtocol,
        isChannelMessage: Bool
    ) async -> LinkPreviewResult {
        let urlString = url.absoluteString

        // Check negative cache first
        if noPreviewAvailable.contains(urlString) {
            return .noPreviewAvailable
        }

        // Check memory and database caches
        if let cached = await checkCaches(urlString: urlString, dataStore: dataStore) {
            return .loaded(cached)
        }

        // Check preferences before network fetch
        guard preferences.shouldAutoResolve(isChannelMessage: isChannelMessage) else {
            return .disabled
        }

        // Prevent duplicate fetches
        guard !inFlightFetches.contains(urlString) else {
            return .loading
        }

        // Network fetch with concurrency limiting
        return await fetchFromNetwork(url: url, urlString: urlString, dataStore: dataStore)
    }

    func manualFetch(
        for url: URL,
        using dataStore: any PersistenceStoreProtocol
    ) async -> LinkPreviewResult {
        let urlString = url.absoluteString

        // Check memory and database caches (skip negative cache for manual retry)
        if let cached = await checkCaches(urlString: urlString, dataStore: dataStore) {
            return .loaded(cached)
        }

        // Prevent duplicate fetches
        guard !inFlightFetches.contains(urlString) else {
            return .loading
        }

        // Clear from negative cache on manual retry
        noPreviewAvailable.remove(urlString)

        return await fetchFromNetwork(url: url, urlString: urlString, dataStore: dataStore)
    }

    // MARK: - Private Helpers

    /// Checks memory cache and database for existing preview data.
    /// Returns the DTO if found and caches in memory if loaded from database.
    private func checkCaches(
        urlString: String,
        dataStore: any PersistenceStoreProtocol
    ) async -> LinkPreviewDataDTO? {
        // Tier 1: Memory cache (instant)
        if let cached = memoryCache.object(forKey: urlString as NSString) {
            return cached.dto
        }

        // Tier 2: Database lookup
        do {
            if let persisted = try await dataStore.fetchLinkPreview(url: urlString) {
                let cost = (persisted.imageData?.count ?? 0) + (persisted.iconData?.count ?? 0)
                memoryCache.setObject(CachedPreview(persisted), forKey: urlString as NSString, cost: cost)
                return persisted
            }
        } catch {
            logger.error("Failed to fetch link preview from database: \(error.localizedDescription)")
        }

        return nil
    }

    private func fetchFromNetwork(
        url: URL,
        urlString: String,
        dataStore: any PersistenceStoreProtocol
    ) async -> LinkPreviewResult {
        inFlightFetches.insert(urlString)

        // Wait for semaphore to limit concurrent fetches
        await fetchSemaphore.wait()

        // Ensure semaphore is released and in-flight tracking is cleaned up
        defer {
            Task { await self.fetchSemaphore.signal() }
            inFlightFetches.remove(urlString)
        }

        // Check for cancellation after acquiring semaphore
        guard !Task.isCancelled else {
            return .noPreviewAvailable
        }

        let metadata = await service.fetchMetadata(for: url)

        guard let metadata else {
            // Cache negative result to avoid repeated fetch attempts
            noPreviewAvailable.insert(urlString)
            return .noPreviewAvailable
        }

        let heroDimensions = metadata.imageData.flatMap(ImageHeaderDecoder.decodeDimensions(from:))
        let dto = LinkPreviewDataDTO(
            url: urlString,
            title: metadata.title,
            imageData: metadata.imageData,
            iconData: metadata.iconData,
            imageWidth: heroDimensions?.width,
            imageHeight: heroDimensions?.height
        )

        // Cache in memory with cost based on image sizes
        let cost = (dto.imageData?.count ?? 0) + (dto.iconData?.count ?? 0)
        memoryCache.setObject(CachedPreview(dto), forKey: urlString as NSString, cost: cost)

        // Persist to database
        do {
            try await dataStore.saveLinkPreview(dto)
        } catch {
            logger.error("Failed to save link preview to database: \(error.localizedDescription)")
        }

        return .loaded(dto)
    }

    func isFetching(_ url: URL) async -> Bool {
        inFlightFetches.contains(url.absoluteString)
    }

    func cachedPreview(for url: URL) async -> LinkPreviewDataDTO? {
        memoryCache.object(forKey: url.absoluteString as NSString)?.dto
    }
}

// MARK: - Supporting Types

/// Wrapper class for NSCache (requires reference type).
/// Immutable after initialization, making @unchecked Sendable safe.
private final class CachedPreview: @unchecked Sendable {
    let dto: LinkPreviewDataDTO
    init(_ dto: LinkPreviewDataDTO) { self.dto = dto }
}
