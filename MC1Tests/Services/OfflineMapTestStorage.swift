import Foundation
import MapLibre

final class OfflineMapTestStorage: MLNOfflineStorage {
  private let directory: URL
  private let sdkDatabaseURL: URL
  private let reportedDatabaseURL: URL
  private var storedPacks: [MLNOfflinePack]?
  var removalError: Error?
  var packsAfterReload: [MLNOfflinePack] = []
  private(set) var reloadCount = 0
  private(set) var hadActivePackAtReload = false
  private(set) var requestedRegions: [MLNTilePyramidOfflineRegion] = []
  private(set) var addedPacks: [OfflineMapTestPack] = []

  init(packs: [MLNOfflinePack]? = nil) throws {
    directory = URL.temporaryDirectory.appending(path: "OfflineMapTests-\(UUID())", directoryHint: .isDirectory)
    sdkDatabaseURL = directory.appending(path: "sdk.db")
    reportedDatabaseURL = directory.appending(path: "reported.db")
    storedPacks = packs
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    try Data().write(to: reportedDatabaseURL)
    super.init()
  }

  deinit {
    // The superclass invalidates its packs; test packs never hold SDK region pointers.
    storedPacks = nil
    try? FileManager.default.removeItem(at: directory)
  }

  override var databasePath: String {
    sdkDatabaseURL.path
  }

  override var databaseURL: URL {
    reportedDatabaseURL
  }

  override var packs: [MLNOfflinePack]? {
    storedPacks
  }

  func publishPacks(_ packs: [MLNOfflinePack]?) {
    willChangeValue(forKey: "packs")
    storedPacks = packs
    didChangeValue(forKey: "packs")
  }

  override func addPack(
    for region: any MLNOfflineRegion,
    withContext context: Data,
    completionHandler completion: MLNOfflinePackAdditionCompletionHandler?
  ) {
    guard let tileRegion = region as? MLNTilePyramidOfflineRegion else {
      completion?(nil, NSError(domain: "OfflineMapTest", code: 2))
      return
    }
    requestedRegions.append(tileRegion)
    let nextID = (storedPacks?.compactMap { $0.regionId?.intValue }.max() ?? 0) + 1
    let pack = OfflineMapTestPack(regionID: nextID, state: .inactive, region: tileRegion, context: context)
    addedPacks.append(pack)
    publishPacks((storedPacks ?? []) + [pack])
    completion?(pack, nil)
  }

  override func removePack(_ pack: MLNOfflinePack, withCompletionHandler completion: MLNOfflinePackRemovalCompletionHandler?) {
    publishPacks(storedPacks?.filter { $0 !== pack })
    (pack as? OfflineMapTestPack)?.storedState = .invalid
    completion?(removalError)
  }

  override func reloadPacks() {
    reloadCount += 1
    hadActivePackAtReload = storedPacks?.contains { $0.state == .active } ?? false
    publishPacks(packsAfterReload)
  }

  func setDatabaseSize(_ bytes: Int) throws {
    try Data(repeating: 0, count: bytes).write(to: reportedDatabaseURL)
  }
}
