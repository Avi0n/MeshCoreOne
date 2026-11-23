import Foundation
@testable import MC1Services
import Testing

/// Runs the vendored MECP freetext suite (`make mecp-sync VER=vX.Y.Z`). An absent key or `null` means the
/// value must not decode, matching MECP's own harness.
@Suite("MECP upstream conformance")
struct MECPConformanceTests {
  private static let supportedSchemaVersion = 2
  /// Conventions MC1 does not decode. A key in neither this set nor `Expectations.CodingKeys` fails the
  /// suite, so a convention added upstream cannot be skipped silently.
  private static let undecodedConventions: Set = ["gps", "reference", "callsign", "language"]

  @Test
  func `every vector matches the envelope and each decoded convention`() throws {
    let manifest = try JSONDecoder().decode(Manifest.self, from: resource("manifest"))
    try #require(manifest.schemaVersion == Self.supportedSchemaVersion)
    let vectors = try JSONDecoder().decode([Vector].self, from: resource("tests"))
    #expect(!vectors.isEmpty)

    for vector in vectors {
      guard let expect = vector.expect else {
        #expect(MECPDecode.decode(vector.input) == nil, "\(vector.title)")
        continue
      }
      let unknown = expect.otherKeys.subtracting(Self.undecodedConventions)
      #expect(unknown.isEmpty, "\(vector.title): unhandled \(unknown.sorted())")

      let message = try #require(MECPDecode.decode(vector.input), "\(vector.title)")
      #expect(message.personCount == expect.pax, "\(vector.title)")
      #expect(message.timestampHHMM == expect.timestampHHMM, "\(vector.title)")
      #expect(message.eta == expect.duration, "\(vector.title)")
    }
  }

  private func resource(_ name: String) throws -> Data {
    let url = try #require(Bundle.module.url(forResource: name, withExtension: "json"))
    return try Data(contentsOf: url)
  }

  private struct Manifest: Decodable {
    let schemaVersion: Int
  }

  private struct Vector: Decodable {
    let title: String
    let input: String
    let expect: Expectations?
  }

  private struct Expectations: Decodable {
    let pax: Int?
    let timestamp: String?
    let duration: Int?
    let otherKeys: Set<String>

    /// `"HH:MM"` in the suite, `"HHMM"` on `MECPMessage`.
    var timestampHHMM: String? {
      timestamp?.replacingOccurrences(of: ":", with: "")
    }

    enum CodingKeys: String, CodingKey, CaseIterable {
      case pax, timestamp, duration
    }

    private struct AnyKey: CodingKey {
      let stringValue: String
      var intValue: Int? {
        nil
      }

      init(stringValue: String) {
        self.stringValue = stringValue
      }

      init?(intValue _: Int) {
        nil
      }
    }

    init(from decoder: Decoder) throws {
      let container = try decoder.container(keyedBy: CodingKeys.self)
      pax = try container.decodeIfPresent(Int.self, forKey: .pax)
      timestamp = try container.decodeIfPresent(String.self, forKey: .timestamp)
      duration = try container.decodeIfPresent(Int.self, forKey: .duration)
      let handled = Set(CodingKeys.allCases.map(\.rawValue))
      otherKeys = try Set(decoder.container(keyedBy: AnyKey.self).allKeys.map(\.stringValue)).subtracting(handled)
    }
  }
}
