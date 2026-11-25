import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("RxLogEntryDTO channel text")
struct RxLogEntryDTOChannelTextTests {
  @Test
  func `group text splits sender name from body`() {
    let entry = makeDTO(payloadType: .groupText, decodedText: "Alice: hi there")
    #expect(entry.channelSenderName == "Alice")
    #expect(entry.messageBody == "hi there")
  }

  @Test
  func `direct message body passes through`() {
    let entry = makeDTO(payloadType: .textMessage, decodedText: "Bob: not a channel prefix")
    #expect(entry.channelSenderName == nil)
    #expect(entry.messageBody == "Bob: not a channel prefix")
  }

  @Test
  func `advert public key extracts 32 bytes when full and nil when truncated or non-advert`() {
    let key = Data(0..<UInt8(ProtocolLimits.publicKeySize))
    let fullAdvert = makeDTO(
      payloadType: .advert,
      packetPayload: key + Data(repeating: 0xAA, count: ProtocolLimits.advertTimestampSize)
    )
    #expect(fullAdvert.advertPublicKey == key)

    let truncatedAdvert = makeDTO(
      payloadType: .advert,
      packetPayload: Data(repeating: 0x01, count: ProtocolLimits.publicKeySize - 1)
    )
    #expect(truncatedAdvert.advertPublicKey == nil)

    let nonAdvert = makeDTO(payloadType: .textMessage, packetPayload: key)
    #expect(nonAdvert.advertPublicKey == nil)
  }

  private func makeDTO(
    payloadType: PayloadType,
    packetPayload: Data = Data(),
    decodedText: String? = nil
  ) -> RxLogEntryDTO {
    let parsed = ParsedRxLogData(
      snr: nil,
      rssi: nil,
      rawPayload: packetPayload,
      routeType: .flood,
      payloadType: payloadType,
      payloadVersion: 0,
      payloadTypeBits: payloadType.rawValue & 0x0F,
      transportCode: nil,
      pathLength: 0,
      pathNodes: [],
      packetPayload: packetPayload
    )
    return RxLogEntryDTO(radioID: UUID(), from: parsed, decodedText: decodedText)
  }
}
