@testable import MeshCore
import Testing

@Suite("LPP Decoder")
struct LPPDecoderTests {
  @Test(arguments: [UInt16(32767), 32768, 40000, 65535])
  func `current round trips the unsigned milliamp range`(milliamps: UInt16) throws {
    var encoder = LPPEncoder()
    encoder.addCurrent(channel: 1, milliamps: milliamps)

    let reading = try #require(LPPDecoder.decode(encoder.encode()).first)
    #expect(reading.channel == 1)
    #expect(reading.type == .current)
    #expect(reading.value == .float(Double(milliamps) / 1000))
  }
}
