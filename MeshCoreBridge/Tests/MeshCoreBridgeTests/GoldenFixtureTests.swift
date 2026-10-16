import Foundation
import Testing
@testable import MeshCoreBridgeKit

/// Freezes the bridge-boundary encoding against checked-in golden files.
///
/// Each event and result encodes to exactly the bytes stored under
/// `fixtures/bridge/`. A drift in any encoder — a renamed field, a changed
/// `Data`/`Date` convention, a new event case — fails these tests, which is the
/// Swift half of the two-sided freeze (§5.6). The Kotlin decoder is tested
/// against the same files.
struct GoldenFixtureTests {
  /// Repo-root `fixtures/bridge` directory, resolved from this file's location.
  static var fixturesDir: URL {
    URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent() // MeshCoreBridgeTests
      .deletingLastPathComponent() // Tests
      .deletingLastPathComponent() // MeshCoreBridge
      .deletingLastPathComponent() // repo root
      .appendingPathComponent("fixtures/bridge")
  }

  static var regenerate: Bool {
    ProcessInfo.processInfo.environment["REGENERATE_BRIDGE_FIXTURES"] == "1"
  }

  @Test("every MeshEvent case encodes to its golden file")
  func eventGoldens() throws {
    let dir = Self.fixturesDir.appendingPathComponent("events")
    for sample in BridgeFixtures.events {
      let bytes = MeshEventEncoder.encode(sample.event).serialized()
      try Self.compareOrRegenerate(bytes, file: dir.appendingPathComponent("\(sample.name).json"))
    }
  }

  @Test("every command result encodes to its golden file")
  func resultGoldens() throws {
    let dir = Self.fixturesDir.appendingPathComponent("results")
    for sample in BridgeFixtures.results {
      try Self.compareOrRegenerate(sample.value.serialized(), file: dir.appendingPathComponent("\(sample.name).json"))
    }
  }

  @Test("the fixture set covers every MeshEvent case and result kind")
  func coverageIsComplete() {
    // Guards against a new MeshEvent case or result kind slipping in without a
    // fixture: the encoders would still compile if a case reused an existing
    // kind, so the counts are asserted explicitly. Update these when adding an
    // event or a command result.
    #expect(BridgeFixtures.events.count == 53)
    #expect(BridgeFixtures.results.count == 29)
    let eventNames = Set(BridgeFixtures.events.map(\.name))
    #expect(eventNames.count == BridgeFixtures.events.count, "duplicate event fixture names")
    let resultNames = Set(BridgeFixtures.results.map(\.name))
    #expect(resultNames.count == BridgeFixtures.results.count, "duplicate result fixture names")
  }

  static func compareOrRegenerate(_ bytes: [UInt8], file: URL) throws {
    if regenerate {
      try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
      // A trailing newline keeps the files git- and editor-friendly; it is not
      // part of the encoding, so it is trimmed before the byte comparison.
      try (Data(bytes) + Data([0x0A])).write(to: file)
      return
    }
    let stored = try Data(contentsOf: file)
    let trimmed = stored.last == 0x0A ? stored.dropLast() : stored[...]
    #expect(Array(trimmed) == bytes, "golden mismatch for \(file.lastPathComponent)")
  }
}
