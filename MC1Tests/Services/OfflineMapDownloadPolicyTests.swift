import MapLibre
@testable import MC1
import Testing

struct OfflineMapDownloadPolicyTests {
  @Test
  func `counts and estimates only original download zooms`() {
    let small = bounds(south: 1, west: 1, north: 1.000001, east: 1.000001)
    #expect(OfflineMapDownloadPolicy.baseTileCount(in: small) == 5)
    #expect(OfflineMapDownloadPolicy.permits(small))
    #expect(OfflineMapService.estimatedDownloadSize(bounds: small, minZoom: 10, maxZoom: 14) == 755_000)
    #expect(OfflineMapService.estimatedDownloadSize(bounds: small, minZoom: 10, maxZoom: 17, layer: .topo) == 730_000)
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
