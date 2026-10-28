import Foundation
import MapLibre

final class OfflineMapTestPack: MLNOfflinePack {
  let storedRegionID: NSNumber
  var storedState: MLNOfflinePackState
  var storedProgress = MLNOfflinePackProgress()
  private let storedContext: Data
  private let storedRegion: MLNTilePyramidOfflineRegion
  private(set) var resumeCount = 0
  private(set) var suspendCount = 0
  private(set) var progressRequestCount = 0

  init(
    regionID: Int,
    state: MLNOfflinePackState,
    region: MLNTilePyramidOfflineRegion? = nil,
    context: Data? = nil
  ) {
    storedRegionID = NSNumber(value: regionID)
    storedState = state
    storedContext = context ?? Data(#"{"name":"Test region","createdAt":0,"layer":"base"}"#.utf8)
    storedRegion = region ?? MLNTilePyramidOfflineRegion(
      styleURL: URL(string: "https://example.invalid/style.json"),
      bounds: MLNCoordinateBounds(
        sw: .init(latitude: 37, longitude: -123),
        ne: .init(latitude: 37.01, longitude: -122.99)
      ),
      fromZoomLevel: 10,
      toZoomLevel: 14
    )
    super.init()
  }

  override var regionId: NSNumber? {
    storedRegionID
  }

  override var state: MLNOfflinePackState {
    storedState
  }

  override var progress: MLNOfflinePackProgress {
    storedProgress
  }

  override var context: Data {
    storedContext
  }

  override var region: any MLNOfflineRegion {
    storedRegion
  }

  override func resume() {
    resumeCount += 1
    storedState = .active
  }

  override func suspend() {
    suspendCount += 1
    storedState = .inactive
  }

  override func requestProgress() {
    progressRequestCount += 1
  }

  func publishProgress() {
    NotificationCenter.default.post(name: .MLNOfflinePackProgressChanged, object: self)
  }
}
