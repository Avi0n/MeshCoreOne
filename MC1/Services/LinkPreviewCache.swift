import Foundation
import MC1Services
import OSLog

// MARK: - Constants

private enum CacheConfig {
  static let maxEntryCount = 100
  static let maxTotalCostBytes = 50 * 1024 * 1024 // 50MB
  static let maxConcurrentFetches = 3
}

/// Two-tier cache for link previews with URL-based deduplication.
/// Uses actor isolation for thread safety without blocking the main thread.
/// Limits concurrent LPMetadataProvider instances to prevent WKWebView spawn bursts.
actor LinkPreviewCache: LinkPreviewCaching {
  private let logger = Logger(subsystem: "com.mc1", category: "LinkPreviewCache")
  private let memoryCache = NSCache<NSString, CachedPreview>()
  private let service: any LinkMetadataFetching
  private nonisolated let preferences = LinkPreviewPreferences()

  /// Shared fetch task per in-flight URL. Concurrent requests for the same
  /// URL await the same task and receive the resolved result, instead of a
  /// `.loading` placeholder that would strand a follower's preview state with
  /// no path back to `.loaded`.
  private var inFlightTasks: [String: Task<LinkPreviewResult, Never>] = [:]

  /// URLs that have been fetched but have no preview available
  private var noPreviewAvailable: Set<String> = []

  /// Semaphore to limit concurrent LPMetadataProvider instances.
  /// Each LPMetadataProvider spawns WKWebView on main thread.
  private let fetchSemaphore = AsyncSemaphore(value: CacheConfig.maxConcurrentFetches)

  init(service: any LinkMetadataFetching = LinkPreviewService()) {
    self.service = service
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

    // Network fetch, coalescing concurrent requests for the same URL
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

  /// Coalesces concurrent fetches for the same URL onto a single shared task.
  /// Followers await that task and receive its resolved result rather than a
  /// `.loading` placeholder. The shared task is independent of any caller's
  /// cancellation, so it completes and warms the cache even if the requesting
  /// cell scrolls away or the conversation switches.
  private func fetchFromNetwork(
    url: URL,
    urlString: String,
    dataStore: any PersistenceStoreProtocol
  ) async -> LinkPreviewResult {
    if let existing = inFlightTasks[urlString] {
      return await existing.value
    }

    let task = Task<LinkPreviewResult, Never> { [self] in
      await performNetworkFetch(url: url, urlString: urlString, dataStore: dataStore)
    }
    inFlightTasks[urlString] = task
    let result = await task.value
    inFlightTasks[urlString] = nil
    return result
  }

  private func performNetworkFetch(
    url: URL,
    urlString: String,
    dataStore: any PersistenceStoreProtocol
  ) async -> LinkPreviewResult {
    // Wait for semaphore to limit concurrent fetches
    await fetchSemaphore.wait()
    defer { Task { await self.fetchSemaphore.signal() } }

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
    inFlightTasks[url.absoluteString] != nil
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
  init(_ dto: LinkPreviewDataDTO) {
    self.dto = dto
  }
}
