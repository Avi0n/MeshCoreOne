import Foundation
import Testing
@testable import MC1Services

struct MCOImageV4CodecTests {
  @Test func decodesEmptyVectorDocument() throws {
    // Nonce, vector/mono header, canonical 1×1 dimensions, default background, END.
    let document = try MCOImageV4Codec.decodeBody(Data([0, 0, 0, 0]))
    #expect(document.width == 1)
    #expect(document.height == 1)
    #expect(document.profile == 0)
    #expect(document.figures.isEmpty)
  }

  @Test func rejectsReservedModeAsUnsupported() {
    #expect(throws: MCOImageCodecError.unsupported) {
      try MCOImageV4Codec.decodeBody(Data([0, 2, 0]))
    }
  }

  @Test func rejectsTruncatedDocumentAsDamaged() {
    #expect(throws: MCOImageCodecError.damaged) {
      try MCOImageV4Codec.decodeBody(Data([0]))
    }
  }

  @Test func boundsZeroDeltaRepeatExpansionBeforeAllocating() throws {
    let supported = try MCOImageV4Codec.decodeBody(repeatedDots(count: 4095))
    #expect(supported.figures.count == 4096)
    let amplified = repeatedDots(count: 1 << 30)
    #expect(amplified.count < 20)
    #expect(throws: MCOImageCodecError.unsupported) {
      try MCOImageV4Codec.decodeBody(amplified)
    }
  }

  @Test func boundsNestedGroupDepth() throws {
    #expect(try MCOImageV4Codec.decodeBody(nestedGroups(depth: 64)).figures.count == 1)
    #expect(throws: MCOImageCodecError.unsupported) {
      try MCOImageV4Codec.decodeBody(nestedGroups(depth: 65))
    }
  }

  @Test func taggedDartCommandFixtures() throws {
    let url = try #require(Bundle.module.url(forResource: "MCOimgV4Fixtures", withExtension: "json", subdirectory: "Fixtures"))
    let root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    let fixtures = try #require(root["fixtures"] as? [[String: Any]])
    #expect(fixtures.count > 50)
    for fixture in fixtures {
      let encoded = try #require(fixture["bodyBase64"] as? String)
      let body = try #require(Data(base64Encoded: encoded))
      if let failure = fixture["error"] as? String {
        #expect(throws: failure == "unsupported" ? MCOImageCodecError.unsupported : .damaged) {
          try MCOImageV4Codec.decodeBody(body)
        }
      } else {
        let actual = dump(try MCOImageV4Codec.decodeBody(body))
        let expected = try #require(fixture["document"] as? [String: Any])
        #expect(try canonicalJSON(actual) == canonicalJSON(expected), "Body: \(encoded)")
      }
    }
  }

  private func canonicalJSON(_ value: [String: Any]) throws -> Data {
    try JSONSerialization.data(withJSONObject: value, options: .sortedKeys)
  }

  private func repeatedDots(count: Int) -> Data {
    var bits = FixtureBits()
    bits.dot()
    bits.put(15, 4)
    bits.put(12, 4)
    bits.put(1, 1)
    bits.put(1, 1)
    bits.put(0, 2)
    bits.put(0, 3)
    bits.put(0, 3)
    bits.compact(count - 2)
    bits.put(0, 1)
    return bits.body()
  }

  private func nestedGroups(depth: Int) -> Data {
    var bits = FixtureBits()
    for _ in 0..<depth {
      bits.put(15, 4)
      bits.put(13, 4)
      bits.compact(0)
    }
    bits.dot()
    return bits.body()
  }

  private struct FixtureBits {
    // Vector/mono, canonical 1×1 dimensions, implicit white background.
    private var bits = [Int](repeating: 0, count: 15)

    mutating func put(_ value: Int, _ count: Int) {
      for shift in 0..<count { bits.append((value >> shift) & 1) }
    }

    mutating func compact(_ value: Int) {
      if value <= 3 { put(0, 1); put(value, 2) } else if value <= 19 {
        put(1, 2); put(value - 4, 4)
      } else if value <= 275 { put(3, 3); put(value - 20, 8) } else {
        put(7, 3)
        var remaining = value
        repeat {
          let low = remaining & 0x7f
          remaining >>= 7
          put(low | (remaining == 0 ? 0 : 0x80), 8)
        } while remaining != 0
      }
    }

    mutating func dot() { put(4, 4); put(1, 2); put(1, 2) }

    mutating func body() -> Data {
      put(0, 4)
      var bytes = [UInt8](repeating: 0, count: (bits.count + 7) / 8)
      for (index, bit) in bits.enumerated() where bit != 0 { bytes[index / 8] |= 1 << (index % 8) }
      return Data([0] + bytes)
    }
  }

  private func dump(_ document: MCOImageV4Document) -> [String: Any] {
    ["width": document.width, "height": document.height, "profile": document.profile,
     "backgroundColor": document.backgroundColor as Any? ?? NSNull(),
     "figures": document.figures.map { dump($0, canvasWidth: document.width) },
     "targetName": document.targetName as Any? ?? NSNull(),
     "replyTimestamp": document.replyTimestamp as Any? ?? NSNull()]
  }

  private func dump(_ style: MCOImageV4Style) -> [String: Any] {
    ["fillColor": style.fillColor as Any? ?? NSNull(), "strokeColor": style.strokeColor as Any? ?? NSNull(),
     "strokeWidth": style.strokeWidth]
  }

  private func point(_ point: MCOImageV4Point) -> [Int] { [point.x, point.y] }

  private func dump(_ figure: MCOImageFigure, canvasWidth: Int) -> [String: Any] {
    var result: [String: Any] = ["style": dump(figure.style)]
    switch figure {
    case let .dot(value, _): result.merge(["kind": "dot", "point": point(value)]) { _, new in new }
    case let .line(start, end, _):
      result.merge(["kind": "line", "start": point(start), "end": point(end)]) { _, new in new }
    case let .rectangle(first, second, third, _):
      result.merge(["kind": "rectangle", "first": point(first), "second": point(second), "third": point(third)]) { _, new in new }
    case let .ellipse(first, second, third, _):
      result.merge(["kind": "ellipse", "first": point(first), "second": point(second), "third": point(third)]) { _, new in new }
    case let .path(points, closed, _):
      result.merge(["kind": "path", "points": points.map(point), "closed": closed]) { _, new in new }
    case let .wave(start, end, depth, closed, _):
      result.merge(["kind": "wave", "start": point(start), "end": point(end), "depth": depth, "closed": closed]) { _, new in new }
    case let .text(origin, width, alignment, fontSize, text, _):
      let resolvedWidth = width ?? max(1, min(canvasWidth, canvasWidth - origin.x))
      result.merge(["kind": "text", "origin": point(origin), "width": resolvedWidth, "alignment": alignment,
                    "fontSize": fontSize, "text": text]) { _, new in new }
    case let .group(figures, _):
      result.merge(["kind": "group", "figures": figures.map { dump($0, canvasWidth: canvasWidth) }]) { _, new in new }
    case let .raster(origin, image):
      result.merge(["kind": "raster", "origin": point(origin), "width": image.width, "height": image.height,
                    "profile": image.profile, "pixels": image.pixels,
                    "transparentColor": image.transparentColor as Any? ?? NSNull()]) { _, new in new }
    }
    return result
  }
}
