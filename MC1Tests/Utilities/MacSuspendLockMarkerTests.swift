import Darwin
import Foundation
@testable import MC1
import Testing

struct MacSuspendLockMarkerTests {
  @Test
  func `Lock path nests the bundle identifier under the sandbox cache`() {
    let url = MacSuspendLockMarker.metalArchiveLockURL(
      cacheDirectory: "/var/folders/C/io.pocketmesh.app/",
      bundleIdentifier: "io.pocketmesh.app"
    )
    #expect(
      url.path == "/var/folders/C/io.pocketmesh.app/io.pocketmesh.app/com.apple.metal/archiveUsage.db/lock.mdb"
    )
  }

  @Test
  func `Marking a lock file sets the suspend attribute`() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("mac-suspend-lock-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let file = directory.appendingPathComponent("lock.mdb")
    #expect(FileManager.default.createFile(atPath: file.path, contents: Data()))

    MacSuspendLockMarker.markLockFile(at: file)

    #expect(attributeBytes(at: file) == [0xFF])
  }

  @Test
  func `Marking a missing file leaves it missing`() {
    let file = FileManager.default.temporaryDirectory
      .appendingPathComponent("mac-suspend-missing-\(UUID().uuidString)")

    MacSuspendLockMarker.markLockFile(at: file)

    #expect(!FileManager.default.fileExists(atPath: file.path))
  }

  private func attributeBytes(at url: URL) -> [UInt8]? {
    let size = getxattr(url.path, MacSuspendLockMarker.attributeName, nil, 0, 0, 0)
    guard size > 0 else { return nil }
    var buffer = [UInt8](repeating: 0, count: size)
    let read = getxattr(url.path, MacSuspendLockMarker.attributeName, &buffer, buffer.count, 0, 0)
    guard read == size else { return nil }
    return buffer
  }
}
