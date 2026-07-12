// MC1Services/Tests/MC1ServicesTests/Services/HeardRepeatsServiceTests.swift
import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("HeardRepeatsService Tests")
struct HeardRepeatsServiceTests {
  // MARK: - ChannelMessageFormat.parse Tests

  @Test
  func `parse with valid format returns sender and message`() {
    let result = ChannelMessageFormat.parse("NodeName: Hello world")

    #expect(result != nil)
    #expect(result?.senderName == "NodeName")
    #expect(result?.messageText == "Hello world")
  }

  @Test
  func `parse with no colon returns nil`() {
    let result = ChannelMessageFormat.parse("No colon here")

    #expect(result == nil)
  }

  @Test
  func `parse with colon at start returns nil`() {
    let result = ChannelMessageFormat.parse(": Message without sender")

    #expect(result == nil)
  }

  @Test
  func `parse with empty message returns empty text`() {
    let result = ChannelMessageFormat.parse("Sender:")

    #expect(result != nil)
    #expect(result?.senderName == "Sender")
    #expect(result?.messageText == "")
  }

  @Test
  func `parse with message containing colons only splits on first`() {
    let result = ChannelMessageFormat.parse("Sender: Time is 10:30:00")

    #expect(result != nil)
    #expect(result?.senderName == "Sender")
    #expect(result?.messageText == "Time is 10:30:00")
  }

  @Test
  func `parse trims whitespace from message`() {
    let result = ChannelMessageFormat.parse("Node:   Padded message   ")

    #expect(result != nil)
    #expect(result?.messageText == "Padded message")
  }

  @Test
  func `parse preserves spaces in sender name`() {
    let result = ChannelMessageFormat.parse("Node With Spaces: Message")

    #expect(result != nil)
    #expect(result?.senderName == "Node With Spaces")
  }

  // MARK: - processForRepeats Matching Tests

  private static let testNodeName = "TestNode"

  private func makeStoreAndService() throws -> (PersistenceStore, HeardRepeatsService) {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    return (store, HeardRepeatsService(dataStore: store))
  }

  /// Builds a decrypted channel-message echo the service can correlate: the
  /// decoded text carries the `"NodeName: body"` format and a matching
  /// `senderTimestamp`.
  private func makeEcho(
    radioID: UUID,
    channelIndex: UInt8,
    senderTimestamp: UInt32,
    body: String,
    senderName: String = testNodeName,
    id: UUID = UUID()
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
      pathLength: 1,
      pathNodes: [0x42],
      packetPayload: Data([0x01, 0x02, 0x03])
    )
    return RxLogEntryDTO(
      id: id,
      radioID: radioID,
      from: parsed,
      channelIndex: channelIndex,
      channelName: "Test",
      decryptStatus: .success,
      senderTimestamp: senderTimestamp,
      decodedText: "\(senderName): \(body)"
    )
  }

  @Test
  func `counts a repeat whose send is far outside the old 10s window`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 2
    // Sent two minutes ago: beyond the removed 10-second wall-clock gate.
    let sendTimestamp = UInt32(Date().timeIntervalSince1970) &- 120
    let messageID = UUID()
    try await store.saveMessage(MessageDTO.testChannelMessage(
      id: messageID,
      radioID: radioID,
      channelIndex: channelIndex,
      text: "north repeater check",
      timestamp: sendTimestamp
    ))
    await service.configure(radioID: radioID, localNodeName: Self.testNodeName)

    let events = service.events()
    let echo = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: sendTimestamp,
      body: "north repeater check"
    )
    let count = await service.processForRepeats(echo)

    #expect(count == 1)
    let repeats = try await store.fetchMessageRepeats(messageID: messageID)
    #expect(repeats.count == 1)
    #expect(repeats.first?.rxLogEntryID == echo.id)

    var iterator = events.makeAsyncIterator()
    let event = await iterator.next()
    #expect(event?.messageID == messageID)
    #expect(event?.count == 1)
  }

  @Test
  func `same RX log entry is counted once`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let sendTimestamp = UInt32(Date().timeIntervalSince1970)
    let messageID = UUID()
    try await store.saveMessage(MessageDTO.testChannelMessage(
      id: messageID,
      radioID: radioID,
      channelIndex: channelIndex,
      text: "hello",
      timestamp: sendTimestamp
    ))
    await service.configure(radioID: radioID, localNodeName: Self.testNodeName)

    let echo = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: sendTimestamp,
      body: "hello"
    )
    let first = await service.processForRepeats(echo)
    let second = await service.processForRepeats(echo)

    #expect(first == 1)
    #expect(second == nil)
    let repeats = try await store.fetchMessageRepeats(messageID: messageID)
    #expect(repeats.count == 1)
  }

  @Test
  func `no match for unknown timestamp or foreign sender`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 1
    let sendTimestamp = UInt32(Date().timeIntervalSince1970)
    try await store.saveMessage(MessageDTO.testChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "hello",
      timestamp: sendTimestamp
    ))
    await service.configure(radioID: radioID, localNodeName: Self.testNodeName)

    let wrongTimestamp = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: sendTimestamp &+ 5,
      body: "hello"
    )
    #expect(await service.processForRepeats(wrongTimestamp) == nil)

    let foreignSender = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: sendTimestamp,
      body: "hello",
      senderName: "SomeoneElse"
    )
    #expect(await service.processForRepeats(foreignSender) == nil)
  }

  @Test
  func `exact text disambiguates messages sharing channel and timestamp`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let channelIndex: UInt8 = 3
    let timestamp = UInt32(Date().timeIntervalSince1970)
    let aID = UUID()
    let bID = UUID()
    try await store.saveMessage(MessageDTO.testChannelMessage(
      id: aID, radioID: radioID, channelIndex: channelIndex, text: "message A", timestamp: timestamp
    ))
    try await store.saveMessage(MessageDTO.testChannelMessage(
      id: bID, radioID: radioID, channelIndex: channelIndex, text: "message B", timestamp: timestamp
    ))

    let matchB = try await store.findSentChannelMessage(
      radioID: radioID, channelIndex: channelIndex, timestamp: timestamp, text: "message B"
    )
    #expect(matchB?.id == bID)
    let matchA = try await store.findSentChannelMessage(
      radioID: radioID, channelIndex: channelIndex, timestamp: timestamp, text: "message A"
    )
    #expect(matchA?.id == aID)
  }
}
