import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("MessagePathArrival assemble")
struct MessagePathArrivalTests {
  @Test
  func `incoming with 0 extras is one first arrival`() {
    let message = incomingMessage(pathNodes: Data([0xAA]), heardRepeats: 0)
    let arrivals = MessagePathArrivals.assemble(message: message, repeats: [])
    #expect(arrivals.count == 1)
    #expect(arrivals[0].isFirst)
    #expect(arrivals[0].id == message.id)
    #expect(arrivals[0].pathNodes == Data([0xAA]))
    #expect(MessagePathArrivals.arrivalCount(for: message) == 1)
  }

  @Test
  func `incoming with 2 extras is canonical then extras in time order`() {
    let createdAt = Date(timeIntervalSince1970: 1_700_000_000)
    let message = incomingMessage(
      pathNodes: Data([0xAA]),
      createdAt: createdAt,
      heardRepeats: 2
    )
    let later = createdAt.addingTimeInterval(5)
    let earlier = createdAt.addingTimeInterval(1)
    let extraB = makeRepeat(
      messageID: message.id,
      receivedAt: later,
      pathNodes: Data([0xBB])
    )
    let extraC = makeRepeat(
      messageID: message.id,
      receivedAt: earlier,
      pathNodes: Data([0xCC])
    )
    let arrivals = MessagePathArrivals.assemble(message: message, repeats: [extraB, extraC])
    #expect(arrivals.count == 3)
    #expect(arrivals[0].isFirst)
    #expect(arrivals[0].id == message.id)
    #expect(arrivals[1].pathNodes == Data([0xCC]))
    #expect(arrivals[2].pathNodes == Data([0xBB]))
    #expect(arrivals[1].isFirst == false)
    #expect(MessagePathArrivals.arrivalCount(for: message) == 3)
  }

  @Test
  func `incoming empty pathNodes with one extra still has a first 0-hop arrival`() {
    let message = incomingMessage(pathNodes: nil, pathLength: 0, heardRepeats: 1)
    let extra = makeRepeat(
      messageID: message.id,
      pathNodes: Data([0xAA]),
      pathLength: 1
    )
    let arrivals = MessagePathArrivals.assemble(message: message, repeats: [extra])
    #expect(arrivals.count == 2)
    #expect(arrivals[0].isFirst)
    #expect(arrivals[0].pathNodes == Data())
    #expect(arrivals[0].isZeroHop)
    #expect(arrivals[0].isPathUnavailable == false)
  }

  @Test
  func `outgoing with 2 repeats has no first arrival`() {
    let message = outgoingMessage(heardRepeats: 2)
    let repeats = [
      makeRepeat(messageID: message.id, pathNodes: Data([0x11])),
      makeRepeat(messageID: message.id, pathNodes: Data([0x22]))
    ]
    let arrivals = MessagePathArrivals.assemble(message: message, repeats: repeats)
    #expect(arrivals.count == 2)
    #expect(arrivals.allSatisfy { !$0.isFirst })
    #expect(MessagePathArrivals.arrivalCount(for: message) == 2)
  }

  @Test
  func `outgoing identical-path echoes stay three arrivals`() {
    let message = outgoingMessage(heardRepeats: 3)
    let shared = Data([0x42])
    let repeats = (0..<3).map { _ in
      makeRepeat(messageID: message.id, pathNodes: shared)
    }
    let arrivals = MessagePathArrivals.assemble(message: message, repeats: repeats)
    #expect(arrivals.count == 3)
    #expect(Set(arrivals.map(\.pathNodes)) == [shared])
  }

  @Test
  func `outgoing with pathNodes nil still reads hops from the arrival`() throws {
    let message = outgoingMessage(heardRepeats: 1)
    #expect(message.pathNodes == nil)
    let extra = makeRepeat(
      messageID: message.id,
      pathNodes: Data([0xA3, 0x7F]),
      pathLength: 2
    )
    let arrivals = MessagePathArrivals.assemble(message: message, repeats: [extra])
    let arrival = try #require(arrivals.first)
    #expect(arrival.isPathUnavailable == false)
    #expect(arrival.pathHops.map(\.hex) == ["A3", "7F"])
    #expect(arrival.pathStringForClipboard == "A3,7F")
  }

  @Test
  func `incoming 0-hop canonical is not Path Unavailable`() throws {
    let message = incomingMessage(pathNodes: Data(), pathLength: 0, heardRepeats: 0)
    let arrival = try #require(MessagePathArrivals.assemble(message: message, repeats: []).first)
    #expect(arrival.isZeroHop)
    #expect(arrival.isPathUnavailable == false)
    #expect(arrival.pathHops.isEmpty)
  }

  private func incomingMessage(
    pathNodes: Data?,
    pathLength: UInt8 = 1,
    createdAt: Date = Date(),
    heardRepeats: Int
  ) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: nil,
      channelIndex: 0,
      text: "flood",
      timestamp: 1_700_000_000,
      createdAt: createdAt,
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: pathLength,
      snr: 8.5,
      pathNodes: pathNodes,
      senderKeyPrefix: nil,
      senderNodeName: "Alice",
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: heardRepeats,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  private func outgoingMessage(heardRepeats: Int) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: nil,
      channelIndex: 0,
      text: "out",
      timestamp: 1_700_000_000,
      createdAt: Date(),
      direction: .outgoing,
      status: .sent,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      pathNodes: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: heardRepeats,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  private func makeRepeat(
    messageID: UUID,
    receivedAt: Date = Date(),
    pathNodes: Data,
    pathLength: UInt8 = 1
  ) -> MessageRepeatDTO {
    MessageRepeatDTO(
      messageID: messageID,
      receivedAt: receivedAt,
      pathNodes: pathNodes,
      pathLength: pathLength,
      snr: 7.0,
      rssi: -80,
      rxLogEntryID: nil
    )
  }
}
