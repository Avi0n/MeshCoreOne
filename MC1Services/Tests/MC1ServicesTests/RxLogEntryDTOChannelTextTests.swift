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

  private func makeDTO(payloadType: PayloadType, decodedText: String?) -> RxLogEntryDTO {
    let parsed = ParsedRxLogData(
      snr: nil,
      rssi: nil,
      rawPayload: Data(),
      routeType: .flood,
      payloadType: payloadType,
      payloadVersion: 0,
      payloadTypeBits: payloadType.rawValue & 0x0F,
      transportCode: nil,
      pathLength: 0,
      pathNodes: [],
      packetPayload: Data()
    )
    return RxLogEntryDTO(radioID: UUID(), from: parsed, decodedText: decodedText)
  }
}
