import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("ChannelRXCorrelation")
struct ChannelRXCorrelationTests {
  @Test
  func `keeps the 0x88 whose body matches the message key`() {
    let aliceKey = DeduplicationKey.contentBased(
      contactID: nil, channelIndex: 0, senderNodeName: "Alice",
      timestamp: 42, content: "one"
    )
    let alice = makeDecodedGroupText(sender: "Alice", body: "one", stamp: 42, path: [0xA1])
    let bob = makeDecodedGroupText(sender: "Bob", body: "two", stamp: 42, path: [0xB2])
    let matching = ChannelRXCorrelation.matching([bob, alice], deduplicationKey: aliceKey)
    #expect(matching.map(\.pathNodes) == [Data([0xA1])])
  }

  @Test
  func `sorts matches by receivedAt ascending`() {
    let aliceKey = DeduplicationKey.contentBased(
      contactID: nil, channelIndex: 0, senderNodeName: "Alice",
      timestamp: 42, content: "one"
    )
    let earlier = Date(timeIntervalSince1970: 1000)
    let later = Date(timeIntervalSince1970: 2000)
    let laterAlice = makeDecodedGroupText(
      sender: "Alice", body: "one", stamp: 42, path: [0xA1], receivedAt: later
    )
    let earlierAlice = makeDecodedGroupText(
      sender: "Alice", body: "one", stamp: 42, path: [0xA2], receivedAt: earlier
    )
    let matching = ChannelRXCorrelation.matching(
      [laterAlice, earlierAlice],
      deduplicationKey: aliceKey
    )
    #expect(matching.map(\.pathNodes) == [Data([0xA2]), Data([0xA1])])
  }

  @Test
  func `drops entries without decodedText`() {
    let aliceKey = DeduplicationKey.contentBased(
      contactID: nil, channelIndex: 0, senderNodeName: "Alice",
      timestamp: 42, content: "one"
    )
    let alice = makeDecodedGroupText(sender: "Alice", body: "one", stamp: 42, path: [0xA1])
    var missingText = makeDecodedGroupText(
      sender: "Alice", body: "one", stamp: 42, path: [0xC3]
    )
    missingText.decodedText = nil
    let matching = ChannelRXCorrelation.matching(
      [missingText, alice],
      deduplicationKey: aliceKey
    )
    #expect(matching.map(\.pathNodes) == [Data([0xA1])])
  }

  @Test
  func `nil key matches nothing`() {
    let alice = makeDecodedGroupText(sender: "Alice", body: "one", stamp: 42, path: [0xA1])
    let matching = ChannelRXCorrelation.matching([alice], deduplicationKey: nil)
    #expect(matching.isEmpty)
  }

  @Test
  func `matches when decoded sender has trailing space`() {
    let aliceKey = DeduplicationKey.contentBased(
      contactID: nil, channelIndex: 0, senderNodeName: "Alice",
      timestamp: 42, content: "hello"
    )
    let padded = makeDecodedGroupText(
      sender: "Alice ", body: "hello", stamp: 42, path: [0xA1]
    )
    let matching = ChannelRXCorrelation.matching([padded], deduplicationKey: aliceKey)
    #expect(matching.map(\.pathNodes) == [Data([0xA1])])
  }

  private func makeDecodedGroupText(
    sender: String,
    body: String,
    stamp: UInt32,
    path: [UInt8],
    receivedAt: Date = Date()
  ) -> RxLogEntryDTO {
    let parsed = ParsedRxLogData(
      snr: 8.0,
      rssi: -70,
      rawPayload: Data([0x01]),
      routeType: .flood,
      payloadType: .groupText,
      payloadVersion: 0,
      payloadTypeBits: 5,
      transportCode: nil,
      pathLength: UInt8(path.count),
      pathNodes: path,
      packetPayload: Data([0x01, 0x02, 0x03])
    )
    return RxLogEntryDTO(
      id: UUID(),
      radioID: UUID(),
      receivedAt: receivedAt,
      from: parsed,
      channelIndex: 0,
      channelName: "Test",
      decryptStatus: .success,
      senderTimestamp: stamp,
      decodedText: "\(sender): \(body)"
    )
  }
}
