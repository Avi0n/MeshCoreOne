import Foundation
import MapLibre
import Network
import os

private let logger = Logger(subsystem: "com.mc1", category: "OfflineMapService")

@Observable
@MainActor
final class OfflineMapService {
  private static let minimumDiskSpaceBytes: Int64 = 100_000_000
  private static let progressRefreshInterval: Duration = .seconds(1)
  private static let pausedRegionsKey = "offlineMapPausedRegionIDs"

  private(set) var packs: [OfflinePack] = []
  private(set) var databaseSize: Int64 = 0
  private(set) var isNetworkAvailable = true
  private(set) var errorMessage: String?

  private let storage: MLNOfflineStorage
  private let defaults: UserDefaults
  private var packsObservation: NSKeyValueObservation?
  private let monitor = NWPathMonitor()
  private var observationTasks: [Task<Void, Never>] = []
  private var pendingLoadTask: Task<Void, Never>?
  private var highWaterMarks: [ObjectIdentifier: Double] = [:]
  private var byteSnapshots: [ObjectIdentifier: (bytes: UInt64, time: ContinuousClock.Instant)] = [:]
  private var downloadSpeeds: [ObjectIdentifier: Int64] = [:]
  private var metadataCache: [ObjectIdentifier: OfflinePackMetadata?] = [:]
  private var deletingPackIDs: Set<ObjectIdentifier> = []
  private var deletingRegionIDs: Set<String> = []
  private var userPausedRegionIDs: Set<String>
  private var pendingResumeRegionIDs: Set<String> = []
  private var resumeWhenLoaded = false
  private var isReloading = false

  init(storage: MLNOfflineStorage = .shared, defaults: UserDefaults = .standard) {
    self.storage = storage
    self.defaults = defaults
    userPausedRegionIDs = Set(defaults.stringArray(forKey: Self.pausedRegionsKey) ?? [])
    let monitor = monitor
    let networkStream = AsyncStream<NWPath> { continuation in
      continuation.onTermination = { _ in monitor.cancel() }
      monitor.pathUpdateHandler = { continuation.yield($0) }
      // NWPathMonitor requires a DispatchQueue; no Swift concurrency alternative exists.
      monitor.start(queue: .global(qos: .utility))
    }
    observationTasks.append(Task { [weak self] in
      for await path in networkStream {
        self?.isNetworkAvailable = path.status == .satisfied
      }
    })

    observationTasks.append(Task { [weak self] in
      for await _ in NotificationCenter.default.notifications(named: .MLNOfflinePackProgressChanged) {
        self?.scheduleLoadPacks()
      }
    })
    observationTasks.append(Task { [weak self] in
      for await notification in NotificationCenter.default.notifications(named: .MLNOfflinePackError) {
        if let error = notification.userInfo?[MLNOfflinePackUserInfoKey.error] as? NSError {
          logger.warning("Offline pack error: \(error.localizedDescription)")
          self?.errorMessage = error.localizedDescription
        }
      }
    })
    observationTasks.append(Task { [weak self] in
      for await _ in NotificationCenter.default.notifications(
        named: .MLNOfflinePackMaximumMapboxTilesReached
      ) {
        logger.warning("Offline pack tile limit reached")
        self?.errorMessage = L10n.Settings.OfflineMaps.Error.tileLimitReached
      }
    })

    excludeDatabaseFromBackup()
    loadPacks()
    updateDatabaseSize()

    packsObservation = storage.observe(\.packs, options: [.initial, .new]) { [weak self] _, change in
      let replaced = change.kind == .setting
      Task { @MainActor [weak self] in
        guard let self else { return }
        if replaced { self.isReloading = false }
        self.loadPacks()
      }
    }
  }

  isolated deinit {
    packsObservation?.invalidate()
    monitor.cancel()
    pendingLoadTask?.cancel()
    for task in observationTasks {
      task.cancel()
    }
  }

  func hasCompletedPack(for layer: OfflineMapLayer) -> Bool {
    packs.contains { $0.layer == layer && $0.isComplete }
  }

  func hasCompletedPack(for layer: OfflineMapLayer, overlapping viewport: MLNCoordinateBounds) -> Bool {
    packs.contains { pack in
      pack.layer == layer && pack.isComplete && pack.bounds.map { $0.overlaps(viewport) } ?? false
    }
  }

  func loadPacks() {
    guard !isReloading, let mlnPacks = storage.packs else { return }
    let regionIDs = Set(mlnPacks.compactMap { $0.regionId?.stringValue })
    userPausedRegionIDs.formIntersection(regionIDs.union(deletingRegionIDs))
    savePausedRegions()
    pendingResumeRegionIDs.formIntersection(regionIDs)
    if resumeWhenLoaded {
      pendingResumeRegionIDs.formUnion(regionIDs)
      resumeWhenLoaded = false
    }
    for pack in mlnPacks where pack.state != .unknown {
      guard let regionID = pack.regionId?.stringValue,
            pendingResumeRegionIDs.remove(regionID) != nil,
            !userPausedRegionIDs.contains(regionID), pack.state == .inactive else { continue }
      if permitsResume(pack) { pack.resume() }
    }
    let currentIDs = Set(mlnPacks.map { ObjectIdentifier($0) })
    let now = ContinuousClock.now

    // Remove tracking data for deleted packs
    highWaterMarks = highWaterMarks.filter { currentIDs.contains($0.key) }
    byteSnapshots = byteSnapshots.filter { currentIDs.contains($0.key) }
    downloadSpeeds = downloadSpeeds.filter { currentIDs.contains($0.key) }
    metadataCache = metadataCache.filter { currentIDs.contains($0.key) }

    packs = mlnPacks.map { mlnPack in
      let packID = ObjectIdentifier(mlnPack)
      let previousFraction = highWaterMarks[packID] ?? 0
      let currentBytes = mlnPack.progress.countOfBytesCompleted

      if let previous = byteSnapshots[packID] {
        let elapsed = now - previous.time
        let seconds = elapsed / .seconds(1)
        if seconds > 0.5, currentBytes > previous.bytes {
          downloadSpeeds[packID] = Int64(Double(currentBytes - previous.bytes) / seconds)
        } else if currentBytes == previous.bytes {
          downloadSpeeds[packID] = 0
        }
      }
      byteSnapshots[packID] = (bytes: currentBytes, time: now)

      let speed = downloadSpeeds[packID]
      if metadataCache[packID] == nil {
        metadataCache[packID] = try? JSONDecoder().decode(OfflinePackMetadata.self, from: mlnPack.context)
      }
      let pack = OfflinePack(pack: mlnPack, metadata: metadataCache[packID] ?? nil, previousFraction: previousFraction, downloadSpeed: speed)
      highWaterMarks[packID] = pack.completedFraction
      return pack
    }

    updateDatabaseSize()

    for mlnPack in mlnPacks where mlnPack.state == .unknown {
      mlnPack.requestProgress()
    }
  }

  private func updateDatabaseSize() {
    let url = storage.databaseURL
    let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
    databaseSize = Int64(size)
  }

  /// Coalesces rapid progress notifications into a single `loadPacks()` call.
  private func scheduleLoadPacks() {
    guard pendingLoadTask == nil else { return }
    pendingLoadTask = Task { [weak self] in
      try? await Task.sleep(for: Self.progressRefreshInterval)
      guard !Task.isCancelled, let self else { return }
      self.pendingLoadTask = nil
      self.loadPacks()
    }
  }

  /// Downloads share MapLibre's request queue with the interactive map renderer.
  func downloadRegion(
    name: String,
    bounds: MLNCoordinateBounds,
    options: OfflineMapDownloadOptions
  ) async throws {
    guard options.topographyMaximumZoom == nil,
          OfflineMapLayer.base.selectableMaximumZoom.contains(options.baseMaximumZoom) else {
      throw OfflineMapError.invalidDetail
    }
    let summary = OfflineMapDownloadPolicy.summary(in: bounds, options: options)
    if !summary.permitsDownload {
      throw OfflineMapError.providerLimitExceeded
    }
    let values = try URL.documentsDirectory.resourceValues(
      forKeys: [.volumeAvailableCapacityForImportantUsageKey]
    )
    if let available = values.volumeAvailableCapacityForImportantUsage,
       available < Self.minimumDiskSpaceBytes || summary.estimatedBytes > available {
      throw OfflineMapError.insufficientDiskSpace
    }

    let encoder = JSONEncoder()
    let now = Date.now

    var pendingPacks: [(region: MLNTilePyramidOfflineRegion, context: Data)] = []
    for (layer, maximumZoom) in options.requests {
      guard let styleURL = layer.styleURL else {
        throw OfflineMapError.missingStyleResource(layer)
      }
      let region = MLNTilePyramidOfflineRegion(
        styleURL: styleURL,
        bounds: bounds,
        fromZoomLevel: Double(OfflineMapDownloadPolicy.minimumZoom),
        toZoomLevel: Double(maximumZoom)
      )
      let metadata = OfflinePackMetadata(name: name, createdAt: now, layer: layer)
      let context = try encoder.encode(metadata)
      pendingPacks.append((region, context))
    }

    for (region, context) in pendingPacks {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
        storage.addPack(for: region, withContext: context) { pack, error in
          if let error {
            continuation.resume(throwing: error)
          } else {
            pack?.resume()
            continuation.resume()
          }
        }
      }
    }
    loadPacks()
  }

  func deletePack(_ pack: OfflinePack) async {
    guard isCurrent(pack), deletingPackIDs.insert(pack.id).inserted else { return }
    defer { deletingPackIDs.remove(pack.id) }
    let regionID = pack.mlnPack.regionId?.stringValue
    let wasActive = pack.mlnPack.state == .active
    if let regionID { deletingRegionIDs.insert(regionID) }
    defer { if let regionID { deletingRegionIDs.remove(regionID) } }
    packs.removeAll { $0.id == pack.id }

    let error: Error? = await withCheckedContinuation { continuation in
      storage.removePack(pack.mlnPack) { error in
        continuation.resume(returning: error)
      }
    }
    if let error {
      logger.error("Failed to delete offline pack: \(error.localizedDescription)")
      errorMessage = error.localizedDescription
      if wasActive, let regionID { pendingResumeRegionIDs.insert(regionID) }
      for remaining in storage.packs ?? [] where remaining.state == .active {
        if let id = remaining.regionId?.stringValue { pendingResumeRegionIDs.insert(id) }
        remaining.suspend()
      }
      isReloading = true
      packs = []
      storage.reloadPacks()
      return
    }
    if let regionID { userPausedRegionIDs.remove(regionID) }
    savePausedRegions()
    highWaterMarks.removeValue(forKey: pack.id)
    byteSnapshots.removeValue(forKey: pack.id)
    downloadSpeeds.removeValue(forKey: pack.id)
    loadPacks()
  }

  func pausePack(_ pack: OfflinePack) {
    guard isCurrent(pack), let regionID = pack.mlnPack.regionId?.stringValue else { return }
    userPausedRegionIDs.insert(regionID)
    pendingResumeRegionIDs.remove(regionID)
    savePausedRegions()
    pack.mlnPack.suspend()
    loadPacks()
  }

  func resumePack(_ pack: OfflinePack) {
    guard isCurrent(pack), let regionID = pack.mlnPack.regionId?.stringValue, permitsResume(pack.mlnPack) else { return }
    userPausedRegionIDs.remove(regionID)
    pendingResumeRegionIDs.remove(regionID)
    savePausedRegions()
    pack.mlnPack.resume()
    loadPacks()
  }

  func resumeAllPacks() {
    resumeWhenLoaded = true
    loadPacks()
  }

  private func isCurrent(_ pack: OfflinePack) -> Bool {
    !isReloading && !deletingPackIDs.contains(pack.id)
      && (storage.packs?.contains { $0 === pack.mlnPack } ?? false)
  }

  private func permitsResume(_ pack: MLNOfflinePack) -> Bool {
    let metadata = try? JSONDecoder().decode(OfflinePackMetadata.self, from: pack.context)
    guard metadata?.layer != .topo else { return false }
    guard let region = pack.region as? MLNTilePyramidOfflineRegion else { return true }
    guard OfflineMapDownloadPolicy.permits(region.bounds, minZoom: region.minimumZoomLevel, maxZoom: region.maximumZoomLevel) else {
      errorMessage = L10n.Settings.OfflineMaps.Error.providerLimitExceeded
      return false
    }
    return true
  }

  private func savePausedRegions() {
    let pausedIDs = userPausedRegionIDs.sorted()
    guard defaults.stringArray(forKey: Self.pausedRegionsKey) != pausedIDs else { return }
    defaults.set(pausedIDs, forKey: Self.pausedRegionsKey)
  }

  func clearError() {
    errorMessage = nil
  }

  private func excludeDatabaseFromBackup() {
    var url = storage.databaseURL
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    do {
      try url.setResourceValues(values)
    } catch {
      logger.error("Failed to exclude offline database from backup: \(error.localizedDescription)")
    }
  }
}
