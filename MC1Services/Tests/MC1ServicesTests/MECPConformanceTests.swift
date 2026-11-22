import Foundation
@testable import MC1Services
import Testing

@Suite("MECP upstream conformance")
struct MECPConformanceTests {
  @Test
  func `v1_4_1 vectors match envelope and person count expectations`() throws {
    let url = try #require(Bundle.module.url(forResource: "freetext-tests-v1.4.1", withExtension: "json"))
    let data = try Data(contentsOf: url)
    let vectors = try JSONDecoder().decode([Vector].self, from: data)
    #expect(!vectors.isEmpty)

    // Asserts envelope validity and person count. The file's other expect fields are left unchecked.
    for vector in vectors {
      guard let expect = vector.expect else {
        #expect(MECPDecode.decode(vector.input) == nil, "\(vector.title)")
        continue
      }
      let message = try #require(MECPDecode.decode(vector.input), "\(vector.title)")
      if expect.hasPaxExpectation {
        #expect(message.personCount == expect.pax, "\(vector.title)")
      }
    }
  }

  private struct Vector: Decodable {
    let title: String
    let input: String
    let expect: Expectations?
  }

  private struct Expectations: Decodable {
    let hasPaxExpectation: Bool
    let pax: Int?

    enum CodingKeys: String, CodingKey {
      case pax
    }

    init(from decoder: Decoder) throws {
      let container = try decoder.container(keyedBy: CodingKeys.self)
      hasPaxExpectation = container.contains(.pax)
      pax = try container.decodeIfPresent(Int.self, forKey: .pax)
    }
  }
}
