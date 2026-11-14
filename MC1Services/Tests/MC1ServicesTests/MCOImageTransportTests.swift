import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("MCOimg channel transport")
struct MCOImageTransportTests {
  private let appPayload = Data(base64Encoded: "E3oBjQBCvgE=")!

  private func datagram(
    payload: Data? = nil,
    name: String = "Peer:名",
    channelIndex: UInt8 = 2,
    dataType: UInt16 = MCOImageTransport.applicationDataType
  ) -> ChannelDatagram {
    let nameBytes = Data(name.utf8)
    return ChannelDatagram(
      channelIndex: channelIndex,
      pathLength: 0,
      dataType: dataType,
      data: payload ?? Data([UInt8(nameBytes.count)]) + nameBytes + appPayload,
      snr: 4.5
    )
  }

  @Test
  func `registered envelope preserves sender and v3 text representation`() throws {
    let image = try #require(MCOImageTransport.decode(datagram()))
    #expect(image.senderName == "Peer:名")
    #expect(image.text == "im3:g/5WjAL`NA")
    #expect(try MCOImageCodec.appPayload(fromText: image.text) == appPayload)
  }

  @Test
  func `v4 text omits subtype byte and unknown image versions survive for placeholders`() throws {
    for version: UInt8 in [0x14, 0x15] {
      let payload = Data([0, version, 0, 0])
      let image = try #require(MCOImageTransport.decode(datagram(payload: payload)))
      #expect(image.text.hasPrefix(version == 0x14 ? "im4:" : "im5:"))
      #expect(try MCOImageCodec.appPayload(fromText: image.text) == Data([version, 0, 0]))
    }
  }

  @Test
  func `complete binary envelope accepts the exact packet boundary and rejects one byte over`() throws {
    let name = String(repeating: "a", count: 155)
    let exact = Data([0x9b, 0x01]) + Data(name.utf8) + appPayload
    #expect(exact.count == MCOImageWireBudget.maximumChannelDataBytes)
    let decoded = try #require(MCOImageTransport.decode(datagram(payload: exact)))
    #expect(decoded.senderName == name)
    #expect(MCOImageTransport.decode(datagram(payload: exact + Data([0]))) == nil)
  }

  @Test
  func `other namespaces and subtypes are ignored`() {
    #expect(MCOImageTransport.decode(datagram(dataType: 0x0121)) == nil)
    #expect(MCOImageTransport.decode(datagram(payload: Data([0, 0x31, 0]))) == nil)
  }

  @Test(arguments: [
    Data(), Data([0x80]), Data([0x80, 0x00, 0x13]),
    Data([0x81, 0x80, 0x80, 0x80, 0x80, 0, 0x13]),
    Data([3, 0x61, 0x62]), Data([1, 0xff, 0x13]), Data([0])
  ])
  func `damaged sender envelopes do not become text messages`(payload: Data) {
    #expect(MCOImageTransport.decode(datagram(payload: payload)) == nil)
  }

  @Test
  func `duplicate window is scoped to radio channel and exact nonce and expires`() {
    let radioID = UUID()
    let time = Date(timeIntervalSince1970: 100)
    var duplicates = MCOImageDatagramDuplicates()
    let packet = datagram()
    #expect({ duplicates.admit(packet, radioID: radioID, receivedAt: time) }())
    #expect({ !duplicates.admit(packet, radioID: radioID, receivedAt: time.addingTimeInterval(1)) }())
    #expect({ duplicates.admit(packet, radioID: UUID(), receivedAt: time.addingTimeInterval(2)) }())
    #expect({ duplicates.admit(datagram(channelIndex: 3), radioID: radioID, receivedAt: time.addingTimeInterval(3)) }())
    var newNonce = packet.data
    newNonce[newNonce.startIndex + 1 + "Peer:名".utf8.count + 1] ^= 1
    #expect({ duplicates.admit(datagram(payload: newNonce), radioID: radioID, receivedAt: time.addingTimeInterval(4)) }())
    #expect({ duplicates.admit(packet, radioID: radioID, receivedAt: time.addingTimeInterval(MCOImageDatagramDuplicates.lifetime)) }())
  }

  @Test
  func `duplicate window evicts old entries at capacity`() {
    let radioID = UUID()
    let time = Date()
    var duplicates = MCOImageDatagramDuplicates()
    for nonce in 0...MCOImageDatagramDuplicates.capacity {
      let packet = datagram(payload: Data([0, 0x13, UInt8(nonce)]))
      #expect({ duplicates.admit(packet, radioID: radioID, receivedAt: time) }())
    }
    #expect({ duplicates.admit(datagram(payload: Data([0, 0x13, 0])), radioID: radioID, receivedAt: time) }())
  }

  private actor Receipt {
    var received: [(ChannelDatagram, DeliveryContext)] = []

    func record(_ packet: ChannelDatagram, context: DeliveryContext) {
      received.append((packet, context))
    }
  }

  @Test
  func `initial sync admits image datagrams while continuing to drain unknown application data`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let session = MockMeshCoreSession()
    let polling = MessagePollingService(session: session, dataStore: store)
    let receipt = Receipt()
    await polling.setChannelDatagramHandler { packet, _, context, _, _, _ in
      await receipt.record(packet, context: context)
    }
    await polling.startMessageEventMonitoring(radioID: radioID)
    let packet = datagram()
    await session.setQueuedMessageResults([
      .channelDatagram(datagram(dataType: 0xffff)), .channelDatagram(packet),
      .channelDatagram(packet), .noMoreMessages
    ])
    #expect(try await polling.pollAllMessages() == 1)
    let received = await receipt.received
    #expect(received.count == 1)
    #expect(received.first?.0 == packet)
    guard case .initialSync = received.first?.1 else {
      Issue.record("Datagram must retain initial-sync delivery context")
      return
    }
    await polling.stopMessageEventMonitoring()
    await session.finishEventStreams()
  }

  @Test
  func `live receipt subscribes to channel datagrams and retains delivery context`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let session = MockMeshCoreSession()
    let polling = MessagePollingService(session: session, dataStore: store)
    let receipt = Receipt()
    await polling.setChannelDatagramHandler { packet, _, context, _, _, _ in
      await receipt.record(packet, context: context)
    }
    await polling.startMessageEventMonitoring(radioID: radioID)
    try await waitUntil("poller should subscribe") { await session.eventSubscriptionCount == 1 }
    await session.yieldEvent(.channelDataReceived(datagram()))
    try await waitUntil("datagram should reach handler") { await receipt.received.count == 1 }
    guard case .live = await receipt.received.first?.1 else {
      Issue.record("Datagram must retain live delivery context")
      return
    }
    await polling.clearMessageHandlers()
    #expect(await !polling.hasMessageHandlersWired)
    await polling.stopMessageEventMonitoring()
    await session.finishEventStreams()
  }
}
