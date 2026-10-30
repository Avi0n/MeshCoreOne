import Foundation
import MapLibre

struct OfflinePack: Identifiable {
  let id: ObjectIdentifier
  let mlnPack: MLNOfflinePack
  let name: String
  let createdAt: Date?
  let layer: OfflineMapLayer
  let completedFraction: Double
  let downloadSpeed: Int64?
  let bounds: MLNCoordinateBounds?

  private let progress: MLNOfflinePackProgress
  private let state: MLNOfflinePackState

  var completedBytes: UInt64 {
    progress.countOfBytesCompleted
  }

  var isComplete: Bool {
    state == .complete
  }

  var isPaused: Bool {
    state == .inactive
  }

  init(pack: MLNOfflinePack, metadata: OfflinePackMetadata?, previousFraction: Double = 0, downloadSpeed: Int64? = nil) {
    id = ObjectIdentifier(pack)
    mlnPack = pack
    progress = pack.progress
    state = pack.state
    bounds = (pack.region as? MLNTilePyramidOfflineRegion)?.bounds

    let rawFraction: Double = if state == .complete {
      1
    } else if progress.countOfResourcesExpected > 0 {
      Double(progress.countOfResourcesCompleted) / Double(progress.countOfResourcesExpected)
    } else {
      0
    }
    completedFraction = max(rawFraction, previousFraction)
    self.downloadSpeed = state == .active ? downloadSpeed : nil

    if let metadata {
      name = metadata.name
      createdAt = metadata.createdAt
      layer = metadata.layer
    } else {
      name = L10n.Settings.OfflineMaps.unknownRegion
      createdAt = nil
      layer = .base
    }
  }
}
