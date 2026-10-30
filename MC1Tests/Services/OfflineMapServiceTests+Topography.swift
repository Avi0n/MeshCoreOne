import Foundation
import MapLibre
@testable import MC1
import Testing

extension OfflineMapServiceTests {
  @Test(arguments: [true, false])
  func `topography cannot resume manually or on foreground`(foreground: Bool) throws {
    let pack = try topographyPack(regionID: 20, state: .inactive)
    let storage = try OfflineMapTestStorage(packs: [pack])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    if foreground {
      service.resumeAllPacks()
    } else {
      try service.resumePack(#require(service.packs.first))
    }
    #expect(pack.resumeCount == 0)
    #expect(service.packs.first?.isPaused == true)
    #expect(service.errorMessage == nil)
  }

  @Test
  func `topography cannot resume after delayed loading and status discovery`() throws {
    let storage = try OfflineMapTestStorage()
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    service.resumeAllPacks()
    let pack = try topographyPack(regionID: 21, state: .unknown)
    storage.publishPacks([pack])
    service.loadPacks()
    #expect(pack.progressRequestCount > 0)
    pack.storedState = .inactive
    service.loadPacks()
    #expect(pack.resumeCount == 0)
    #expect(service.packs.first?.isPaused == true)
    #expect(service.errorMessage == nil)
  }

  @Test
  func `completed topography stays accessible and deletable`() async throws {
    let pack = try topographyPack(regionID: 27, state: .complete)
    let storage = try OfflineMapTestStorage(packs: [pack])
    let service = try OfflineMapService(storage: storage, defaults: makeDefaults())
    #expect(service.hasCompletedPack(for: .topo))
    try await service.deletePack(#require(service.packs.first))
    #expect(service.packs.isEmpty)
  }

  private func topographyPack(regionID: Int, state: MLNOfflinePackState) throws -> OfflineMapTestPack {
    let region = MLNTilePyramidOfflineRegion(
      styleURL: OfflineMapLayer.topo.styleURL,
      bounds: MLNCoordinateBounds(sw: .init(latitude: 1, longitude: 1), ne: .init(latitude: 1.01, longitude: 1.01)),
      fromZoomLevel: 10,
      toZoomLevel: 17
    )
    let context = try JSONEncoder().encode(OfflinePackMetadata(name: "Existing topography", createdAt: .now, layer: .topo))
    return OfflineMapTestPack(regionID: regionID, state: state, region: region, context: context)
  }
}
