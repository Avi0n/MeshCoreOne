import Foundation
import MapLibre
@testable import MC1
import Testing

@Suite("Offline map service", .serialized)
@MainActor
struct OfflineMapServiceTests {
  @Test
  func `progress refreshes during continuous notifications`() async throws {
    let pack = OfflineMapTestPack(regionID: 6, state: .active)
    let storage = try OfflineMapTestStorage(packs: [pack])
    let initialBytes = 512
    let downloadedBytes = 4096
    try storage.setDatabaseSize(initialBytes)
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    #expect(service.databaseSize == Int64(initialBytes))
    await Task.yield()

    try storage.setDatabaseSize(downloadedBytes)
    for completedBytes in 1...12 {
      pack.storedProgress.countOfBytesCompleted = UInt64(completedBytes)
      pack.publishProgress()
      try await Task.sleep(for: .milliseconds(200))
    }

    #expect(try #require(service.packs.first).completedBytes > 1)
    #expect(service.databaseSize == Int64(downloadedBytes))
  }

  @Test
  func `metadata decodes legacy base pack`() throws {
    let data = Data(#"{"name":"Legacy","createdAt":0}"#.utf8)
    let decoded = try JSONDecoder().decode(OfflinePackMetadata.self, from: data)
    #expect(decoded.name == "Legacy")
    #expect(decoded.layer == .base)
    let roundTrip = try JSONDecoder().decode(
      OfflinePackMetadata.self, from: JSONEncoder().encode(decoded)
    )
    #expect(roundTrip.layer == .base)
    #expect(roundTrip.createdAt == decoded.createdAt)
  }

  @Test
  func `loaded packs appear after delayed storage initialization`() async throws {
    let storage = try OfflineMapTestStorage()
    let defaults = try makeDefaults()
    let service = OfflineMapService(storage: storage, defaults: defaults)
    #expect(service.packs.isEmpty)

    try await Task.sleep(for: .milliseconds(2200))
    let pack = OfflineMapTestPack(regionID: 1, state: .complete)
    storage.publishPacks([pack])

    try await waitUntil("Storage loading must publish existing packs") {
      service.packs.count == 1
    }
    #expect(service.packs.first?.isComplete == true)
  }

  @Test
  func `foreground resume waits for unknown pack status`() async throws {
    let pack = OfflineMapTestPack(regionID: 2, state: .unknown)
    let completed = OfflineMapTestPack(regionID: 9, state: .complete)
    let storage = try OfflineMapTestStorage(packs: [pack, completed])
    let defaults = try makeDefaults()
    let service = OfflineMapService(storage: storage, defaults: defaults)
    service.resumeAllPacks()
    #expect(pack.resumeCount == 0)
    #expect(pack.progressRequestCount > 0)

    await Task.yield()
    pack.storedState = .inactive
    pack.publishProgress()
    try await waitUntil("Restored incomplete pack must resume after status discovery") {
      pack.resumeCount == 1
    }
    pack.publishProgress()
    service.resumeAllPacks()
    #expect(pack.resumeCount == 1)
    #expect(completed.resumeCount == 0)
  }

  @Test
  func `explicit pause survives service and pack recreation`() async throws {
    let defaults = try makeDefaults()
    let original = OfflineMapTestPack(regionID: 3, state: .active)
    let firstStorage = try OfflineMapTestStorage(packs: [original])
    var firstService: OfflineMapService? = OfflineMapService(storage: firstStorage, defaults: defaults)
    let snapshot = try #require(firstService?.packs.first)
    firstService?.pausePack(snapshot)
    #expect(original.state == .inactive)
    firstService = nil

    let restored = OfflineMapTestPack(regionID: 3, state: .inactive)
    let secondStorage = try OfflineMapTestStorage(packs: [restored])
    let secondService = OfflineMapService(storage: secondStorage, defaults: defaults)
    secondService.resumeAllPacks()
    await Task.yield()
    #expect(restored.resumeCount == 0)
    #expect(secondService.packs.first?.isPaused == true)

    try secondService.resumePack(#require(secondService.packs.first))
    #expect(restored.resumeCount == 1)
  }

  @Test
  func `failed deletion surfaces error and reloads valid packs`() async throws {
    let deleted = OfflineMapTestPack(regionID: 4, state: .inactive)
    let downloading = OfflineMapTestPack(regionID: 5, state: .active)
    let recovered = OfflineMapTestPack(regionID: 4, state: .inactive)
    let restoredDownload = OfflineMapTestPack(regionID: 5, state: .inactive)
    let storage = try OfflineMapTestStorage(packs: [deleted, downloading])
    storage.removalError = NSError(domain: "OfflineMapTest", code: 1, userInfo: [
      NSLocalizedDescriptionKey: "The map could not be deleted."
    ])
    storage.packsAfterReload = [recovered, restoredDownload]
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())

    let snapshot = try #require(service.packs.first)
    service.pausePack(snapshot)
    await service.deletePack(snapshot)
    try await waitUntil("Failed deletion must restore the on-disk pack") {
      service.packs.contains { $0.id == ObjectIdentifier(recovered) }
    }
    #expect(service.errorMessage != nil)
    #expect(storage.reloadCount == 1)
    #expect(downloading.suspendCount > 0)
    #expect(!storage.hadActivePackAtReload)
    #expect(restoredDownload.resumeCount == 1)
    #expect(!service.packs.contains { $0.id == ObjectIdentifier(deleted) })
    service.resumeAllPacks()
    #expect(recovered.resumeCount == 0)
    #expect(service.packs.first?.isPaused == true)
    service.clearError()
    #expect(service.errorMessage == nil)
  }

  @Test
  func `oversized existing pack cannot resume manually or on foreground`() throws {
    let region = MLNTilePyramidOfflineRegion(
      styleURL: OfflineMapLayer.base.styleURL,
      bounds: MLNCoordinateBounds(
        sw: .init(latitude: -80, longitude: -180),
        ne: .init(latitude: 80, longitude: 180)
      ),
      fromZoomLevel: 10,
      toZoomLevel: 14
    )
    let pack = OfflineMapTestPack(regionID: 8, state: .inactive, region: region)
    let storage = try OfflineMapTestStorage(packs: [pack])
    let defaults = try makeDefaults()
    let service = OfflineMapService(storage: storage, defaults: defaults)
    try service.pausePack(#require(service.packs.first))
    let pausedIDs = defaults.stringArray(forKey: "offlineMapPausedRegionIDs")

    try service.resumePack(#require(service.packs.first))
    #expect(defaults.stringArray(forKey: "offlineMapPausedRegionIDs") == pausedIDs)
    #expect(region.minimumZoomLevel == 10)
    #expect(region.maximumZoomLevel == 14)
    #expect(pack.resumeCount == 0)
    #expect(service.errorMessage == L10n.Settings.OfflineMaps.Error.providerLimitExceeded)
    service.clearError()

    let foregroundService = try OfflineMapService(storage: storage, defaults: makeDefaults())
    foregroundService.resumeAllPacks()
    #expect(pack.resumeCount == 0)
    #expect(foregroundService.errorMessage == L10n.Settings.OfflineMaps.Error.providerLimitExceeded)
    #expect(foregroundService.packs.first?.isPaused == true)
  }

  @Test
  func `terminal inactive state does not repeat an earlier foreground resume`() async throws {
    let pack = OfflineMapTestPack(regionID: 10, state: .inactive)
    let storage = try OfflineMapTestStorage(packs: [pack])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    service.resumeAllPacks()
    #expect(pack.resumeCount == 1)
    #expect(service.packs.first?.isPaused == false)
    await Task.yield()

    pack.storedState = .inactive
    pack.publishProgress()

    try await waitUntil("Terminal inactive state must remain paused without another resume request") {
      service.packs.first?.isPaused == true
    }
    #expect(pack.resumeCount == 1)
  }

  func makeDefaults() throws -> UserDefaults {
    try #require(UserDefaults(suiteName: "OfflineMapTests-\(UUID())"))
  }
}
