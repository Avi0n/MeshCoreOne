import Foundation
import MapLibre
@testable import MC1
import Testing

extension OfflineMapServiceTests {
  @Test(arguments: [(0, nil as Int?), (5, 11)])
  func `selected maxima and styles reach SDK unchanged`(baseMaximum: Int, topography: Int?) async throws {
    let storage = try OfflineMapTestStorage(packs: [])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    let bounds = MLNCoordinateBounds(
      sw: .init(latitude: 1, longitude: 1), ne: .init(latitude: 1.000001, longitude: 1.000001)
    )
    try await service.downloadRegion(name: "Captured name", bounds: bounds, options: .init(baseMaximumZoom: baseMaximum, topographyMaximumZoom: topography))
    #expect(storage.requestedRegions.count == (topography == nil ? 1 : 2))
    let base = try #require(storage.requestedRegions.first)
    #expect(base.minimumZoomLevel == 0)
    #expect(base.maximumZoomLevel == Double(baseMaximum))
    #expect(base.styleURL == OfflineMapLayer.base.styleURL)
    #expect(MLNCoordinateBoundsEqualToCoordinateBounds(base.bounds, bounds))
    #expect(service.packs.allSatisfy { $0.name == "Captured name" })
    #expect(storage.addedPacks.allSatisfy { $0.resumeCount == 1 })
    if let topography {
      let topo = try #require(storage.requestedRegions.last)
      #expect(topo.minimumZoomLevel == 0)
      #expect(topo.maximumZoomLevel == Double(topography))
      #expect(topo.styleURL == OfflineMapLayer.topo.styleURL)
      #expect(service.packs.map(\.layer) == [.base, .topo])
    } else {
      #expect(service.packs.map(\.layer) == [.base])
    }
  }

  @Test(arguments: [(-1, nil as Int?), (15, nil), (0, -1), (0, 17)])
  func `invalid enabled detail is refused before any SDK addition`(base: Int, topography: Int?) async throws {
    let storage = try OfflineMapTestStorage(packs: [])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    do {
      try await service.downloadRegion(name: "Invalid", bounds: worldBounds, options: .init(baseMaximumZoom: base, topographyMaximumZoom: topography))
      Issue.record("Invalid detail must be refused")
    } catch OfflineMapError.invalidDetail {
      #expect(storage.requestedRegions.isEmpty)
    }
  }

  @Test(arguments: [
    (42.31387756616186, 43.321181429266616, 0.7635498046875, true),
    (-1.927736780140595, -0.005493164054084669, 0.5438232421875, false),
  ])
  func `service enforces exact provider boundary before addition`(south: Double, north: Double, east: Double, permitted: Bool) async throws {
    let storage = try OfflineMapTestStorage(packs: [])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    let bounds = MLNCoordinateBounds(sw: .init(latitude: south, longitude: 0.0054931640625), ne: .init(latitude: north, longitude: east))
    do {
      try await service.downloadRegion(name: "Boundary", bounds: bounds, options: .init(baseMaximumZoom: 14, topographyMaximumZoom: nil))
      #expect(permitted)
      #expect(storage.requestedRegions.count == 1)
    } catch OfflineMapError.providerLimitExceeded {
      #expect(permitted == false)
      #expect(storage.requestedRegions.isEmpty)
    }
  }

  @Test
  func `small byte estimate cannot bypass service provider limit`() async throws {
    let storage = try OfflineMapTestStorage(packs: [])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    let bounds = MLNCoordinateBounds(sw: .init(latitude: -85, longitude: -180), ne: .init(latitude: 0, longitude: 180))
    let options = OfflineMapDownloadOptions(baseMaximumZoom: 5, topographyMaximumZoom: nil)
    let summary = OfflineMapDownloadPolicy.summary(in: bounds, options: options)
    #expect(summary.baseTileCount == 3413)
    #expect(summary.permitsDownload == false)
    try #require(summary.estimatedBytes < 50_000_000)
    do {
      try await service.downloadRegion(name: "Overview", bounds: bounds, options: options)
      Issue.record("Tile-count limit must apply even below fifty MB")
    } catch OfflineMapError.providerLimitExceeded {
      #expect(storage.requestedRegions.isEmpty)
    }
  }

  @Test
  func `selected range capacity is checked before addition`() async throws {
    let options = OfflineMapDownloadOptions(baseMaximumZoom: 0, topographyMaximumZoom: 16)
    let summary = OfflineMapDownloadPolicy.summary(in: worldBounds, options: options)
    let capacity = try #require(URL.documentsDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage)
    try #require(summary.permitsDownload)
    try #require(summary.estimatedBytes > capacity)
    let storage = try OfflineMapTestStorage(packs: [])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    do {
      try await service.downloadRegion(name: "World", bounds: worldBounds, options: options)
      Issue.record("Selected estimate must fit current capacity")
    } catch OfflineMapError.insufficientDiskSpace {
      #expect(storage.requestedRegions.isEmpty)
    }
  }

  @Test(arguments: [true, false])
  func `broad low detail stored pack resumes using its original range`(foreground: Bool) throws {
    let region = MLNTilePyramidOfflineRegion(styleURL: OfflineMapLayer.base.styleURL, bounds: worldBounds, fromZoomLevel: 0, toZoomLevel: 0)
    let pack = OfflineMapTestPack(regionID: 11, state: .inactive, region: region)
    let storage = try OfflineMapTestStorage(packs: [pack])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    #expect(OfflineMapDownloadPolicy.permits(worldBounds) == false)
    if foreground {
      service.resumeAllPacks()
    } else {
      try service.resumePack(#require(service.packs.first))
    }
    #expect(pack.resumeCount == 1)
    #expect(service.errorMessage == nil)
    #expect(region.minimumZoomLevel == 0)
    #expect(region.maximumZoomLevel == 0)
  }

  @Test
  func `legacy topography maximum seventeen remains resumable`() throws {
    let region = MLNTilePyramidOfflineRegion(styleURL: OfflineMapLayer.topo.styleURL, bounds: worldBounds, fromZoomLevel: 10, toZoomLevel: 17)
    let context = try JSONEncoder().encode(OfflinePackMetadata(name: "Legacy topography", createdAt: .now, layer: .topo))
    let pack = OfflineMapTestPack(regionID: 12, state: .inactive, region: region, context: context)
    let storage = try OfflineMapTestStorage(packs: [pack])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    try service.resumePack(#require(service.packs.first))
    #expect(pack.resumeCount == 1)
    #expect(region.minimumZoomLevel == 10)
    #expect(region.maximumZoomLevel == 17)
    #expect(service.errorMessage == nil)
  }

  private var worldBounds: MLNCoordinateBounds {
    MLNCoordinateBounds(sw: .init(latitude: -85, longitude: -180), ne: .init(latitude: 85, longitude: 180))
  }
}
