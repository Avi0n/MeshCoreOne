import Foundation
import MapLibre
@testable import MC1
import Testing

struct OfflineMapDownloadPolicyTests {
  @Test(arguments: [
    (OfflineMapLayer.base, 0.0, 2, Int64(520_000)),
    (.base, 5, 12, 620_000),
    (.base, 14, 21, 915_000),
    (.topo, 0, 1, 510_000),
    (.topo, 5, 6, 560_000),
    (.topo, 16, 17, 820_000),
  ])
  func `source ranges and byte coefficients match native source levels`(
    layer: OfflineMapLayer, maximum: Double, tiles: Int, bytes: Int64
  ) {
    let summary = OfflineMapDownloadPolicy.summary(in: tinyBounds, minZoom: 0, maxZoom: maximum, layer: layer)
    #expect(summary.tileCount == tiles)
    #expect(OfflineMapService.estimatedDownloadSize(bounds: tinyBounds, minZoom: 0, maxZoom: maximum, layer: layer) == bytes)
  }

  @Test(arguments: [
    (OfflineMapLayer.base, 10.25, 12.5, 3, Int64(585_000)),
    (.topo, 10.25, 12.5, 4, 595_000),
    (.base, 10, 14, 5, 755_000),
    (.topo, 10, 17, 7, 715_000),
    (.base, 0, 17, 21, 915_000),
    (.topo, 0, 17, 17, 820_000),
    (.base, 15, 17, 0, 500_000),
    (.topo, 17, 17, 0, 500_000),
  ])
  func `stored source ranges intersect without normalizing camera zoom`(
    layer: OfflineMapLayer, minimum: Double, maximum: Double, tiles: Int, bytes: Int64
  ) {
    let summary = OfflineMapDownloadPolicy.summary(in: tinyBounds, minZoom: minimum, maxZoom: maximum, layer: layer)
    #expect(summary.tileCount == tiles)
    #expect(summary.estimatedBytes == bytes)
  }

  @Test
  func `optional topography changes only its own accounting`() {
    let base = OfflineMapDownloadPolicy.summary(in: tinyBounds, options: .init(baseMaximumZoom: 14, topographyMaximumZoom: nil))
    let overview = OfflineMapDownloadPolicy.summary(in: tinyBounds, options: .init(baseMaximumZoom: 14, topographyMaximumZoom: 0))
    let detailed = OfflineMapDownloadPolicy.summary(in: tinyBounds, options: .init(baseMaximumZoom: 14, topographyMaximumZoom: 16))
    #expect(base.baseTileCount == 21)
    #expect(overview.baseTileCount == 21)
    #expect(detailed.baseTileCount == 21)
    #expect(base.estimatedBytes == 915_000)
    #expect(overview.estimatedBytes == 1_425_000)
    #expect(detailed.estimatedBytes == 1_735_000)
  }

  @Test(arguments: [
    (42.31387756616186, 43.321181429266616, 0.7635498046875, 3000, true),
    (-1.927736780140595, -0.005493164054084669, 0.5438232421875, 3001, false),
  ])
  func `provider boundary counts both base sources`(south: Double, north: Double, east: Double, tiles: Int, permitted: Bool) {
    let region = bounds(south: south, west: 0.0054931640625, north: north, east: east)
    #expect(OfflineMapDownloadPolicy.baseTileCount(in: region) == tiles)
    #expect(OfflineMapDownloadPolicy.permits(region) == permitted)
  }

  @Test
  func `world copies deduplicate within each source`() {
    let world = bounds(south: -90, west: -540, north: 90, east: 540)
    let summary = OfflineMapDownloadPolicy.summary(in: world, minZoom: 0, maxZoom: 0, layer: .base)
    #expect(summary.tileCount == 5)
    #expect(summary.estimatedBytes == 550_000)
  }

  @Test
  func `bundled topography source matches accounting profile`() throws {
    let url = try #require(OfflineMapLayer.topo.styleURL)
    let style = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    let sources = try #require(style["sources"] as? [String: [String: Any]])
    let source = try #require(sources["topo"])
    #expect(sources.count == 1)
    #expect(source["type"] as? String == "raster")
    #expect(source["tileSize"] as? Int == 256)
    #expect(source["minzoom"] == nil)
    #expect(source["maxzoom"] as? Int == 17)
  }

  private var tinyBounds: MLNCoordinateBounds {
    bounds(south: 1, west: 1, north: 1.000001, east: 1.000001)
  }

  @Test
  func `equivalent date line bounds have equal tile budgets`() {
    let eastern = bounds(south: 1, west: 179.9, north: 1.01, east: 180.1)
    let western = bounds(south: 1, west: -180.1, north: 1.01, east: -179.9)
    let wrapped = bounds(south: 1, west: 179.9, north: 1.01, east: -179.9)
    #expect(OfflineMapDownloadPolicy.baseTileCount(in: eastern) == OfflineMapDownloadPolicy.baseTileCount(in: western))
    #expect(OfflineMapDownloadPolicy.baseTileCount(in: eastern) == OfflineMapDownloadPolicy.baseTileCount(in: wrapped))
  }

  @Test
  func `overlap is symmetric across date line and rejects separate regions`() {
    let east = bounds(south: -1, west: 179, north: 1, east: 181)
    let west = bounds(south: -0.5, west: -180, north: 0.5, east: -179.5)
    #expect(east.overlaps(west))
    #expect(west.overlaps(east))
    #expect(!east.overlaps(bounds(south: -1, west: -170, north: 1, east: -160)))
    #expect(!east.overlaps(bounds(south: 10, west: 179, north: 11, east: 181)))
    #expect(bounds(south: -1, west: -180, north: 1, east: 180).overlaps(west))
  }

  private func bounds(south: Double, west: Double, north: Double, east: Double) -> MLNCoordinateBounds {
    MLNCoordinateBounds(sw: .init(latitude: south, longitude: west), ne: .init(latitude: north, longitude: east))
  }
}
