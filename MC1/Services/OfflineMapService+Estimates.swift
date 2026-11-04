import MapLibre

extension OfflineMapService {
  nonisolated static func estimatedDownloadSize(
    bounds: MLNCoordinateBounds,
    minZoom: Double,
    maxZoom: Double,
    layer: OfflineMapLayer = .base
  ) -> Int64 {
    OfflineMapDownloadPolicy.summary(in: bounds, minZoom: minZoom, maxZoom: maxZoom, layer: layer).estimatedBytes
  }
}
