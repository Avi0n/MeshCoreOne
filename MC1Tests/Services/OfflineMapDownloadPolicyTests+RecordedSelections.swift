import MapLibre
@testable import MC1
import Testing

extension OfflineMapDownloadPolicyTests {
  @Test
  func `recorded native selections preserve counts and calibrated estimates`() {
    for receipt in recordedSelections {
      let summary = OfflineMapDownloadPolicy.summary(in: receipt.bounds, options: receipt.options)
      #expect(summary.tileCount == receipt.tiles)
      #expect(summary.estimatedBytes == receipt.correctedEstimate)
    }
  }

  private var recordedSelections: [RecordedSelection] {
    [
      .init(south: 47.60, west: -122.35, north: 47.68, east: -122.25, maximum: 14,
            tiles: 82, correctedEstimate: 11_835_752),
      .init(south: 40.70, west: -74.03, north: 40.80, east: -73.93, maximum: 12,
            tiles: 28, correctedEstimate: 7_095_752),
      .init(south: 39, west: -117.1, north: 39.1, east: -117, maximum: 14,
            tiles: 84, correctedEstimate: 12_165_752),
      .init(south: 35, west: -130.1, north: 35.1, east: -130, maximum: 13,
            tiles: 36, correctedEstimate: 7_645_752),
      .init(south: 46.57, west: 7.95, north: 46.60, east: 7.99, maximum: 11, topography: 13,
            tiles: 43, correctedEstimate: 7_250_974),
      .init(south: 47.60, west: -122.30, north: 47.646, east: -122.254, maximum: 14, topography: 16,
            tiles: 729, correctedEstimate: 37_834_974),
    ]
  }

  private struct RecordedSelection {
    let south: Double
    let west: Double
    let north: Double
    let east: Double
    let maximum: Int
    var topography: Int?
    let tiles: Int
    let correctedEstimate: Int64

    var bounds: MLNCoordinateBounds {
      MLNCoordinateBounds(sw: .init(latitude: south, longitude: west), ne: .init(latitude: north, longitude: east))
    }

    var options: OfflineMapDownloadOptions {
      .init(baseMaximumZoom: maximum, topographyMaximumZoom: topography)
    }
  }
}
