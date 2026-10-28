import Foundation
import MapLibre

enum OfflineMapDownloadPolicy {
  static let minimumZoom = 0
  static let maximumBaseTiles = 3000
  private static let defaultBytesPerTile: Int64 = 10000
  private static let packOverheadBytes: Int64 = 500_000
  private static let mapTileSize = 512.0
  private static let mercatorLatitudeLimit = 85.0511287798066
  private static let fullLongitudeSpan = 360.0
  private static let halfLongitudeSpan = fullLongitudeSpan / 2

  struct Summary {
    let tileCount: Int
    let baseTileCount: Int
    let estimatedBytes: Int64

    var permitsDownload: Bool {
      baseTileCount <= OfflineMapDownloadPolicy.maximumBaseTiles
    }
  }

  private struct Source {
    let zoomRange: ClosedRange<Int>
    let tileSize: Double
    let isRaster: Bool
    let bytesPerTile: [Int: Int64]
  }

  private static let openMapTiles = Source(
    zoomRange: 0...14, tileSize: 512, isRaster: false,
    bytesPerTile: [10: 15000, 11: 25000, 12: 45000, 13: 70000, 14: 100_000]
  )
  private static let naturalEarth = Source(
    zoomRange: 0...6, tileSize: 256, isRaster: true, bytesPerTile: [:]
  )
  private static let openTopoMap = Source(
    zoomRange: 0...17, tileSize: 256, isRaster: true,
    bytesPerTile: [10: 15000, 11: 18000, 12: 22000, 13: 25000, 14: 30000, 15: 35000, 16: 40000, 17: 45000]
  )

  static func summary(in bounds: MLNCoordinateBounds, options: OfflineMapDownloadOptions) -> Summary {
    options.requests.reduce(Summary(tileCount: 0, baseTileCount: 0, estimatedBytes: 0)) { total, request in
      let layer = summary(in: bounds, minZoom: Double(minimumZoom), maxZoom: Double(request.maximumZoom), layer: request.layer)
      return Summary(
        tileCount: total.tileCount + layer.tileCount,
        baseTileCount: total.baseTileCount + layer.baseTileCount,
        estimatedBytes: total.estimatedBytes + layer.estimatedBytes
      )
    }
  }

  static func summary(in bounds: MLNCoordinateBounds, minZoom: Double, maxZoom: Double, layer: OfflineMapLayer) -> Summary {
    let sources = switch layer {
    case .base: [openMapTiles, naturalEarth]
    case .topo: [openTopoMap]
    }
    var count = 0
    var bytes = packOverheadBytes
    for source in sources {
      // Match MapLibre’s source zoom conversion so estimates and provider limits count the tiles it downloads.
      let offset = log2(mapTileSize / source.tileSize)
      let first = max(Int((minZoom + offset).rounded(source.isRaster ? .toNearestOrAwayFromZero : .down)), source.zoomRange.lowerBound)
      let last = min(Int((maxZoom + offset).rounded(source.isRaster ? .toNearestOrAwayFromZero : .down)), source.zoomRange.upperBound)
      guard first <= last else { continue }
      for zoom in first...last {
        let tiles = tileCount(in: bounds, zoom: zoom)
        count += tiles
        bytes += Int64(tiles) * (source.bytesPerTile[zoom] ?? defaultBytesPerTile)
      }
    }
    return Summary(tileCount: count, baseTileCount: layer == .base ? count : 0, estimatedBytes: bytes)
  }

  static func permits(_ bounds: MLNCoordinateBounds, minZoom: Double = Double(minimumZoom), maxZoom: Double = Double(OfflineMapLayer.base.defaultMaximumZoom)) -> Bool {
    summary(in: bounds, minZoom: minZoom, maxZoom: maxZoom, layer: .base).permitsDownload
  }

  static func baseTileCount(in bounds: MLNCoordinateBounds) -> Int {
    summary(in: bounds, minZoom: Double(minimumZoom), maxZoom: Double(OfflineMapLayer.base.defaultMaximumZoom), layer: .base).baseTileCount
  }

  static func tileCount(in bounds: MLNCoordinateBounds, zoom: Int) -> Int {
    let dimension = Double(1 << zoom)
    let west = bounds.sw.longitude
    var east = bounds.ne.longitude
    if east < west { east += fullLongitudeSpan }
    let width = min(east - west, fullLongitudeSpan)
    let firstX = floor((west + halfLongitudeSpan) / fullLongitudeSpan * dimension)
    let lastX = floor((west + width + halfLongitudeSpan) / fullLongitudeSpan * dimension)
    let columns = min(dimension, lastX - firstX + 1)

    func tileY(_ latitude: Double) -> Double {
      let clamped = min(mercatorLatitudeLimit, max(-mercatorLatitudeLimit, latitude))
      let radians = clamped * .pi / halfLongitudeSpan
      let y = floor((1 - log(tan(radians) + 1 / cos(radians)) / .pi) / 2 * dimension)
      return min(dimension - 1, max(0, y))
    }
    return Int(columns * (abs(tileY(bounds.sw.latitude) - tileY(bounds.ne.latitude)) + 1))
  }
}
