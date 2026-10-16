import Foundation
@testable import MeshCore
import Testing

@Suite("WiFiFrameCodec Tests")
struct WiFiFrameCodecTests {
  @Test
  func `Encodes frame with correct delimiter and length`() {
    let payload = Data([0x01, 0x02, 0x03])
    let encoded = WiFiFrameCodec.encode(payload)

    // Expected: '<' (0x3C) + length (3, 0) little-endian + payload
    #expect(encoded.count == 6)
    #expect(encoded[0] == 0x3C) // '<' delimiter
    #expect(encoded[1] == 0x03) // length low byte
    #expect(encoded[2] == 0x00) // length high byte
    #expect(encoded[3] == 0x01)
    #expect(encoded[4] == 0x02)
    #expect(encoded[5] == 0x03)
  }

  @Test
  func `Encodes empty frame`() {
    let payload = Data()
    let encoded = WiFiFrameCodec.encode(payload)

    #expect(encoded.count == 3)
    #expect(encoded[0] == 0x3C)
    #expect(encoded[1] == 0x00)
    #expect(encoded[2] == 0x00)
  }

  @Test
  func `Decodes single complete frame`() {
    // '>' + length (3, 0) + payload
    let data = Data([0x3E, 0x03, 0x00, 0x01, 0x02, 0x03])
    var decoder = WiFiFrameDecoder()

    let frames = decoder.decode(data)

    #expect(frames.count == 1)
    #expect(frames[0] == Data([0x01, 0x02, 0x03]))
  }

  @Test
  func `Decodes multiple frames in one chunk`() {
    let data = Data([
      0x3E, 0x02, 0x00, 0xAA, 0xBB, // frame 1
      0x3E, 0x01, 0x00, 0xCC // frame 2
    ])
    var decoder = WiFiFrameDecoder()

    let frames = decoder.decode(data)

    #expect(frames.count == 2)
    #expect(frames[0] == Data([0xAA, 0xBB]))
    #expect(frames[1] == Data([0xCC]))
  }

  @Test
  func `Buffers incomplete frame`() {
    var decoder = WiFiFrameDecoder()

    // Send header only
    let frames1 = decoder.decode(Data([0x3E, 0x05, 0x00]))
    #expect(frames1.isEmpty)

    // Send partial payload
    let frames2 = decoder.decode(Data([0x01, 0x02]))
    #expect(frames2.isEmpty)

    // Complete the frame
    let frames3 = decoder.decode(Data([0x03, 0x04, 0x05]))
    #expect(frames3.count == 1)
    #expect(frames3[0] == Data([0x01, 0x02, 0x03, 0x04, 0x05]))
  }

  @Test
  func `Handles frame split across chunks`() {
    var decoder = WiFiFrameDecoder()

    // First chunk: delimiter only
    let frames1 = decoder.decode(Data([0x3E]))
    #expect(frames1.isEmpty)

    // Second chunk: length + partial payload
    let frames2 = decoder.decode(Data([0x03, 0x00, 0xAA]))
    #expect(frames2.isEmpty)

    // Third chunk: rest of payload
    let frames3 = decoder.decode(Data([0xBB, 0xCC]))
    #expect(frames3.count == 1)
    #expect(frames3[0] == Data([0xAA, 0xBB, 0xCC]))
  }
}
