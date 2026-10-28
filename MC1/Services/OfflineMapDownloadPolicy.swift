import Foundation
import MapLibre

enum OfflineMapDownloadPolicy {
  static let minimumZoom = 10
  static let maximumBaseTiles = 3000
  private static let mercatorLatitudeLimit = 85.0511287798066
  private static let fullLongitudeSpan = 360.0
  private static let halfLongitudeSpan = fullLongitudeSpan / 2

  static func permits(_ bounds: MLNCoordinateBounds) -> Bool {
    baseTileCount(in: bounds) <= maximumBaseTiles
  }

  static func baseTileCount(in bounds: MLNCoordinateBounds) -> Int {
    (minimumZoom...Int(OfflineMapLayer.base.maxDownloadZoom)).reduce(0) {
      $0 + tileCount(in: bounds, zoom: $1)
    }
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
