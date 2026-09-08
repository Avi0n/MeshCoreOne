@testable import MC1Services
import Testing

@Suite("ProtocolLimits channel RX-log budget")
struct ProtocolLimitsTests {
  @Test func `composed cap is the last 9-block size`() {
    #expect(ProtocolLimits.maxChannelMessageTotalLength == 139)
    #expect(ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: 4) == 133)
    #expect(ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: 31) == 106)
    #expect(ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: 200) == 0)
  }

  @Test func `Region 1-hop at the cap fits a 172-byte companion frame`() {
    let frame = ProtocolLimits.groupTextRxLogFrameByteCount(
      composedUTF8Count: ProtocolLimits.maxChannelMessageTotalLength,
      includesTransportCodes: true,
      pathByteCount: ProtocolLimits.minHeardRepeatPathBytes
    )
    #expect(frame == 157)
    #expect(frame <= ProtocolLimits.rxLogHeardRepeatFrameBudget)
  }

  @Test func `Region 1-hop at 147 overflows 172 and still fits 176`() {
    let frame = ProtocolLimits.groupTextRxLogFrameByteCount(
      composedUTF8Count: 147,
      includesTransportCodes: true,
      pathByteCount: ProtocolLimits.minHeardRepeatPathBytes
    )
    #expect(frame == 173)
    #expect(frame > ProtocolLimits.rxLogHeardRepeatFrameBudget)
    #expect(frame <= ProtocolLimits.maxFrameSize)
  }
}
