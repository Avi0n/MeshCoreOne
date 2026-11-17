import Darwin
import Foundation
import os

private let logger = Logger(subsystem: "com.mc1", category: "MacSuspendLockMarker")

/// Marks Metal's `archiveUsage.db/lock.mdb` so RunningBoard can suspend an iOS app on Mac.
/// A held lock outside the container is fatal unless the file has the suspend attribute.
enum MacSuspendLockMarker {
  static let attributeName = "com.apple.runningboard.can-suspend-locked"
  private static let markerByte: UInt8 = 0xFF
  private static let markerLength = 1
  private static let attributePosition: UInt32 = 0
  private static let attributeOptions: Int32 = 0
  private static let cachePathCapacity = 1024
  private static let metalDirectoryName = "com.apple.metal"
  private static let archiveDatabaseDirectoryName = "archiveUsage.db"
  private static let lockFileName = "lock.mdb"

  static func markIfNeeded() {
    guard ProcessInfo.processInfo.isiOSAppOnMac else { return }
    guard let cacheDirectory = darwinUserCacheDirectory() else { return }
    guard let bundleIdentifier = Bundle.main.bundleIdentifier else { return }
    let lockURL = metalArchiveLockURL(cacheDirectory: cacheDirectory, bundleIdentifier: bundleIdentifier)
    markLockFile(at: lockURL)
  }

  static func metalArchiveLockURL(cacheDirectory: String, bundleIdentifier: String) -> URL {
    URL(fileURLWithPath: cacheDirectory, isDirectory: true)
      .appendingPathComponent(bundleIdentifier, isDirectory: true)
      .appendingPathComponent(metalDirectoryName, isDirectory: true)
      .appendingPathComponent(archiveDatabaseDirectoryName, isDirectory: true)
      .appendingPathComponent(lockFileName)
  }

  static func markLockFile(at url: URL) {
    let path = url.path
    guard FileManager.default.fileExists(atPath: path) else { return }
    var marker = markerByte
    let result = setxattr(path, attributeName, &marker, markerLength, attributePosition, attributeOptions)
    guard result != 0 else { return }
    let error = errno
    logger.error("Metal lock marker failed: \(error, privacy: .public)")
  }

  private static func darwinUserCacheDirectory() -> String? {
    var buffer = [CChar](repeating: 0, count: cachePathCapacity)
    let length = confstr(_CS_DARWIN_USER_CACHE_DIR, &buffer, buffer.count)
    guard length > 1, length <= buffer.count else { return nil }
    return String(cString: buffer)
  }
}
