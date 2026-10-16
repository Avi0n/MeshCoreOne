import Foundation
@testable import MeshCore
import Testing

/// The Swift side of the routing path-length codec freeze. `fixtures/path/path-len-vectors.json` is
/// asserted here and by the Kotlin `PathEncodingFixtureTest`, so the Kotlin port of this codec cannot
/// drift from the source of record without one of the two suites going red.
@Suite("PathEncoding fixtures")
struct PathEncodingFixtureTests {
  private struct DecodeVector: Decodable {
    let label: String
    let encoded: UInt8
    let hashSize: Int?
    let hopCount: Int?
    let byteLength: Int?
  }

  private struct EncodeVector: Decodable {
    let label: String
    let hashSize: Int
    let hopCount: Int
    let encoded: UInt8
  }

  private struct Vectors: Decodable {
    let decode: [DecodeVector]
    let encode: [EncodeVector]
  }

  /// `<repo>/fixtures/path`, resolved by ascending from this source file until the directory holding
  /// `MeshCore/` is found, so the package can move without breaking the lookup.
  private static var fixtureURL: URL {
    var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    let fileManager = FileManager.default
    while url.pathComponents.count > 1 {
      let packageDir = url.appendingPathComponent("MeshCore", isDirectory: true)
      var isDir: ObjCBool = false
      if fileManager.fileExists(atPath: packageDir.path, isDirectory: &isDir), isDir.boolValue {
        return url.appendingPathComponent("fixtures/path/path-len-vectors.json")
      }
      url = url.deletingLastPathComponent()
    }
    fatalError("Could not locate repo root (no ancestor directory contains MeshCore/)")
  }

  private static func vectors() throws -> Vectors {
    try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: fixtureURL))
  }

  @Test
  func `every decode vector matches`() throws {
    for vector in try Self.vectors().decode {
      let decoded = decodePathLen(vector.encoded)
      guard let expectedHashSize = vector.hashSize else {
        #expect(decoded == nil, "reserved mode must not decode: \(vector.label)")
        continue
      }
      #expect(decoded?.hashSize == expectedHashSize, "\(vector.label)")
      #expect(decoded?.hopCount == vector.hopCount, "\(vector.label)")
      #expect(decoded?.byteLength == vector.byteLength, "\(vector.label)")
    }
  }

  @Test
  func `every encode vector matches`() throws {
    for vector in try Self.vectors().encode {
      let encoded = encodePathLen(hashSize: vector.hashSize, hopCount: vector.hopCount)
      #expect(encoded == vector.encoded, "encode drift for \(vector.label)")
    }
  }

  /// Every path-length byte, plus every legal encode pair, projected from `decodePathLen` /
  /// `encodePathLen`. Kotlin reads this file instead of repeating the bit layout.
  @Test
  func `exhaustive table matches Swift`() throws {
    let generated = try Self.tableData()
    if ProcessInfo.processInfo.environment["MC1_WRITE_PATH_TABLE"] == "1" {
      try generated.write(to: Self.tableURL, options: .atomic)
    }
    let stored = try Data(contentsOf: Self.tableURL)
    let decoded = try JSONDecoder().decode(PathLenTable.self, from: stored)
    #expect(decoded.maxPathHashMode == PathEncoding.maxPathHashMode)
    #expect(decoded.maxHopCount == PathEncoding.maxHopCount)
    #expect(decoded.maxPathBytes == PathEncoding.maxPathBytes)
    #expect(decoded.decode.count == 256)
    for row in decoded.decode {
      let actual = decodePathLen(UInt8(row.encoded))
      #expect(actual?.hashSize == row.hashSize, "decode \(row.encoded)")
      #expect(actual?.hopCount == row.hopCount, "decode \(row.encoded)")
      #expect(actual?.byteLength == row.byteLength, "decode \(row.encoded)")
    }
    for row in decoded.encode {
      let actual = encodePathLen(hashSize: row.hashSize, hopCount: row.hopCount)
      #expect(actual == UInt8(row.encoded), "encode \(row.hashSize) \(row.hopCount)")
    }
    let regenerated = try JSONDecoder().decode(PathLenTable.self, from: generated)
    #expect(regenerated.decode.count == decoded.decode.count)
    #expect(regenerated.encode.count == decoded.encode.count)
  }

  private struct PathLenDecodeRow: Codable {
    let encoded: Int
    let hashSize: Int?
    let hopCount: Int?
    let byteLength: Int?
  }

  private struct PathLenEncodeRow: Codable {
    let hashSize: Int
    let hopCount: Int
    let encoded: Int
  }

  private struct PathLenTable: Codable {
    let maxPathHashMode: Int
    let maxHopCount: Int
    let maxPathBytes: Int
    let decode: [PathLenDecodeRow]
    let encode: [PathLenEncodeRow]
  }

  private static var tableURL: URL {
    fixtureURL.deletingLastPathComponent().appendingPathComponent("path-len-table.json")
  }

  private static func tableData() throws -> Data {
    let decode = (0...255).map { value -> PathLenDecodeRow in
      let decoded = decodePathLen(UInt8(value))
      return PathLenDecodeRow(
        encoded: value,
        hashSize: decoded?.hashSize,
        hopCount: decoded?.hopCount,
        byteLength: decoded?.byteLength
      )
    }
    var encode: [PathLenEncodeRow] = []
    for hashSize in 1...3 {
      for hopCount in [0, 1, 2, 3, 7, 63, 64, 100, 255] {
        encode.append(
          PathLenEncodeRow(
            hashSize: hashSize,
            hopCount: hopCount,
            encoded: Int(encodePathLen(hashSize: hashSize, hopCount: hopCount))
          )
        )
      }
    }
    let table = PathLenTable(
      maxPathHashMode: PathEncoding.maxPathHashMode,
      maxHopCount: PathEncoding.maxHopCount,
      maxPathBytes: PathEncoding.maxPathBytes,
      decode: decode,
      encode: encode
    )
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    return try encoder.encode(table)
  }
}
