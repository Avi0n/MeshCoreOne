import Foundation
import MapLibre
@testable import MC1
import Testing

extension OfflineMapServiceTests {
  @Test(arguments: [0, 5])
  func `selected base maxima and style reach SDK unchanged`(baseMaximum: Int) async throws {
    let storage = try OfflineMapTestStorage(packs: [])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    let bounds = MLNCoordinateBounds(
      sw: .init(latitude: 1, longitude: 1), ne: .init(latitude: 1.000001, longitude: 1.000001)
    )
    try await service.downloadRegion(name: "Captured name", bounds: bounds, options: .init(baseMaximumZoom: baseMaximum, topographyMaximumZoom: nil))
    #expect(storage.requestedRegions.count == 1)
    let base = try #require(storage.requestedRegions.first)
    #expect(base.minimumZoomLevel == 0)
    #expect(base.maximumZoomLevel == Double(baseMaximum))
    #expect(base.styleURL == OfflineMapLayer.base.styleURL)
    #expect(MLNCoordinateBoundsEqualToCoordinateBounds(base.bounds, bounds))
    #expect(service.packs.allSatisfy { $0.name == "Captured name" })
    #expect(storage.addedPacks.allSatisfy { $0.resumeCount == 1 })
    #expect(service.packs.map(\.layer) == [.base])
  }

  @Test(arguments: [(-1, nil as Int?), (15, nil), (0, 16)])
  func `unsupported download options are refused before any SDK addition`(base: Int, topography: Int?) async throws {
    let storage = try OfflineMapTestStorage(packs: [])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    do {
      try await service.downloadRegion(name: "Invalid", bounds: worldBounds, options: .init(baseMaximumZoom: base, topographyMaximumZoom: topography))
      Issue.record("Unsupported download options must be refused")
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

  private var worldBounds: MLNCoordinateBounds {
    MLNCoordinateBounds(sw: .init(latitude: -85, longitude: -180), ne: .init(latitude: 85, longitude: 180))
  }
}
