import Foundation
@testable import MeshCore
import Testing

@Suite("EventFilter factories (FU2 migration)")
struct EventFilterFactoryTests {
  // MARK: - rxLogData

  @Test
  func `rxLogData matches .rxLogData and rejects unrelated events`() {
    let filter = EventFilter.rxLogData
    let log = ParsedRxLogData(
      snr: 6.0,
      rssi: -72,
      rawPayload: Data([0x00]),
      routeType: .flood,
      payloadType: .textMessage,
      payloadVersion: 0,
      payloadTypeBits: 2,
      transportCode: nil,
      pathLength: 0,
      pathNodes: [],
      packetPayload: Data()
    )

    #expect(filter.matches(.rxLogData(log)))
    #expect(!filter.matches(.advertisement(publicKey: Data([0xAA]))))
    #expect(!filter.matches(.ok(value: nil)))
  }

  // MARK: - anyAdvertisement

  @Test
  func `anyAdvertisement matches .advertisement and rejects unrelated events`() {
    let filter = EventFilter.anyAdvertisement

    #expect(filter.matches(.advertisement(publicKey: Data([0xAA, 0xBB]))))
    #expect(filter.matches(.advertisement(publicKey: Data([0xFF]))))
    #expect(!filter.matches(.ok(value: nil)))
    #expect(!filter.matches(.error(code: 1)))
  }

  // MARK: - anyContactMessage

  @Test
  func `anyContactMessage matches .contactMessageReceived and rejects unrelated events`() {
    let filter = EventFilter.anyContactMessage
    let msg = ContactMessage(
      senderPublicKeyPrefix: Data([0x01, 0x02]),
      pathLength: 0,
      textType: 0,
      senderTimestamp: Date(),
      signature: nil,
      text: "hello",
      snr: nil
    )

    #expect(filter.matches(.contactMessageReceived(msg)))
    #expect(!filter.matches(.advertisement(publicKey: Data([0xAA]))))
    #expect(!filter.matches(.ok(value: nil)))
  }

  // MARK: - anyChannelMessage

  @Test
  func `anyChannelMessage matches .channelMessageReceived and rejects unrelated events`() {
    let filter = EventFilter.anyChannelMessage
    let msg = ChannelMessage(
      channelIndex: 3,
      pathLength: 0,
      textType: 0,
      senderTimestamp: Date(),
      text: "hi",
      snr: nil
    )

    #expect(filter.matches(.channelMessageReceived(msg)))
    #expect(!filter.matches(.advertisement(publicKey: Data([0xAA]))))
    #expect(!filter.matches(.noMoreMessages))
  }

  // MARK: - anyLoginSuccess

  @Test
  func `anyLoginSuccess matches .loginSuccess and rejects unrelated events`() {
    let filter = EventFilter.anyLoginSuccess
    let info = LoginInfo(
      permissions: 0x01,
      isAdmin: false,
      publicKeyPrefix: Data([0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02])
    )

    #expect(filter.matches(.loginSuccess(info)))
    #expect(!filter.matches(.loginFailed(publicKeyPrefix: nil)))
    #expect(!filter.matches(.ok(value: nil)))
  }

  // MARK: - anyLoginFailed

  @Test
  func `anyLoginFailed matches .loginFailed and rejects unrelated events`() {
    let filter = EventFilter.anyLoginFailed
    let info = LoginInfo(
      permissions: 0x00,
      isAdmin: false,
      publicKeyPrefix: Data([0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02])
    )

    #expect(filter.matches(.loginFailed(publicKeyPrefix: Data([0xAA, 0xBB]))))
    #expect(filter.matches(.loginFailed(publicKeyPrefix: nil)))
    #expect(!filter.matches(.loginSuccess(info)))
    #expect(!filter.matches(.ok(value: nil)))
  }

  // MARK: - discoverResponse

  @Test
  func `discoverResponse matches exact tag and rejects other tags or events`() {
    let targetTag = Data([0x01, 0x02, 0x03, 0x04])
    let filter = EventFilter.discoverResponse(tag: targetTag)
    let matching = DiscoverResponse(
      nodeType: 2,
      snrIn: 5.0,
      snr: 6.5,
      rssi: -80,
      pathLength: 1,
      tag: targetTag,
      publicKey: Data(repeating: 0xAA, count: 32)
    )
    let otherTag = DiscoverResponse(
      nodeType: 2,
      snrIn: 5.0,
      snr: 6.5,
      rssi: -80,
      pathLength: 1,
      tag: Data([0x09, 0x09, 0x09, 0x09]),
      publicKey: Data(repeating: 0xBB, count: 32)
    )

    #expect(filter.matches(.discoverResponse(matching)))
    #expect(!filter.matches(.discoverResponse(otherTag)))
    #expect(!filter.matches(.messagesWaiting))
  }
}
