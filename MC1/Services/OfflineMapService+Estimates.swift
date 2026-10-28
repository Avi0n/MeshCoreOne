import MapLibre

extension OfflineMapService {
  /// Estimated download size using per-zoom average byte sizes.
  nonisolated static func estimatedDownloadSize(
    bounds: MLNCoordinateBounds,
    minZoom: Int,
    maxZoom: Int,
    layer: OfflineMapLayer = .base
  ) -> Int64 {
    let bytesPerTile: [Int: Int64] = switch layer {
    case .base:
      // OpenFreeMap vector tiles (OpenMapTiles schema, max z14).
      // Populated land regions average 30-150 KB per tile at these zooms.
      [
        10: 15000, 11: 25000, 12: 45000,
        13: 70000, 14: 100_000,
      ]
    case .topo:
      // OpenTopoMap PNG raster tiles (256px, max z17).
      [
        10: 15000, 11: 18000, 12: 22000,
        13: 25000, 14: 30000, 15: 35000,
        16: 40000, 17: 45000,
      ]
    }

    // Non-tile resources: style JSON, TileJSON manifests, sprites, glyph PBFs
    let overhead: Int64 = 500_000
    let defaultBytesPerTile: Int64 = 10000

    var total: Int64 = 0
    for z in minZoom...maxZoom {
      let tileCount = OfflineMapDownloadPolicy.tileCount(in: bounds, zoom: z)
      total += Int64(tileCount) * (bytesPerTile[z] ?? defaultBytesPerTile)
    }
    return total + overhead
  }
}
