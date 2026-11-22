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

  @Test
  func `parse trims leading and trailing whitespace from sender`() {
    let result = ChannelMessageFormat.parse("Alice : hello")
    #expect(result?.senderName == "Alice")
    #expect(result?.messageText == "hello")
  }

  // MARK: - processForRepeats Matching Tests

  private static let testNodeName = "TestNode"

  // MARK: - DM echoes

  @Test
  func `DM echo from our key to an in-flight recipient is recorded`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let contactID = UUID()
    let messageID = UUID()
    let selfKey = Data(repeating: 0x11, count: 32)
    try await store.saveContact(.testContact(
      id: contactID,
      radioID: radioID,
      publicKey: Data(repeating: 0x22, count: 32)
    ))
    try await store.saveMessage(.testDirectMessage(
      id: messageID,
      radioID: radioID,
      contactID: contactID,
      status: .retrying
    ))
    await service.configure(radioID: radioID, selfPublicKey: selfKey)

    let echo = makeDMEcho(radioID: radioID, recipientHash: 0x22, senderHash: 0x11)
    let count = await service.processForRepeats(echo)

    #expect(count == 1)
    let repeats = try await store.fetchMessageRepeats(messageID: messageID)
    #expect(repeats.count == 1)
  }

  @Test
  func `DM echo is ignored when the sender hash is not ours or no send is active`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let contactID = UUID()
    let selfKey = Data(repeating: 0x11, count: 32)
    try await store.saveContact(.testContact(
      id: contactID,
      radioID: radioID,
      publicKey: Data(repeating: 0x22, count: 32)
    ))
    let delivered = UUID()
    try await store.saveMessage(.testDirectMessage(
      id: delivered,
      radioID: radioID,
      contactID: contactID,
      status: .delivered
    ))
    await service.configure(radioID: radioID, selfPublicKey: selfKey)

    // Someone else's DM to the same contact.
    let foreign = await service.processForRepeats(
      makeDMEcho(radioID: radioID, recipientHash: 0x22, senderHash: 0x99)
    )
    // Ours, but the only message already delivered.
    let settled = await service.processForRepeats(
      makeDMEcho(radioID: radioID, recipientHash: 0x22, senderHash: 0x11)
    )

    #expect(foreign == nil)
    #expect(settled == nil)
    #expect(try await store.fetchMessageRepeats(messageID: delivered).isEmpty)
  }

  private func makeDMEcho(radioID: UUID, recipientHash: UInt8, senderHash: UInt8) -> RxLogEntryDTO {
    let parsed = ParsedRxLogData(
      snr: 6.0,
      rssi: -80,
      rawPayload: Data([0x01]),
      routeType: .flood,
      payloadType: .textMessage,
      payloadVersion: 0,
      payloadTypeBits: 2,
      transportCode: nil,
      pathLength: 1,
      pathNodes: [0x42],
      packetPayload: Data([recipientHash, senderHash, 0xAA, 0xBB, 0xCC])
    )
    return RxLogEntryDTO(id: UUID(), radioID: radioID, receivedAt: Date(), from: parsed)
  }

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
    id: UUID = UUID(),
    pathNodes: [UInt8] = [0x42],
    pathLength: UInt8 = 1,
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
      pathLength: pathLength,
      pathNodes: pathNodes,
      packetPayload: Data([0x01, 0x02, 0x03])
    )
    return RxLogEntryDTO(
      id: id,
      radioID: radioID,
      receivedAt: receivedAt,
      from: parsed,
      channelIndex: channelIndex,
      channelName: "Test",
      decryptStatus: .success,
      senderTimestamp: senderTimestamp,
      decodedText: "\(senderName): \(body)"
    )
  }

  private func incomingChannelMessage(
    id: UUID = UUID(),
    radioID: UUID,
    channelIndex: UInt8,
    text: String,
    senderName: String,
    wireTimestamp: UInt32,
    pathNodes: Data?,
    pathLength: UInt8,
    timestampCorrected: Bool = false,
    receiveTime: Date = Date()
  ) -> MessageDTO {
    var message = MessageDTO.testChannelMessage(
      id: id,
      radioID: radioID,
      channelIndex: channelIndex,
      text: text,
      timestamp: timestampCorrected ? UInt32(receiveTime.timeIntervalSince1970) : wireTimestamp,
      createdAt: receiveTime,
      direction: .incoming,
      pathLength: pathLength,
      senderNodeName: senderName
    )
    message.pathNodes = pathNodes
    message.timestampCorrected = timestampCorrected
    message.senderTimestamp = timestampCorrected ? wireTimestamp : nil
    message.deduplicationKey = DeduplicationKey.contentBased(
      contactID: nil,
      channelIndex: channelIndex,
      senderNodeName: senderName,
      timestamp: wireTimestamp,
      content: text
    )
    return message
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
    await service.configure(radioID: radioID)

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
    await service.configure(radioID: radioID)

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
  func `no match for unknown timestamp`() async throws {
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
    await service.configure(radioID: radioID)

    let wrongTimestamp = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: sendTimestamp &+ 5,
      body: "hello"
    )
    #expect(await service.processForRepeats(wrongTimestamp) == nil)
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

  /// Air prefix is not a join key. Body, timestamp, and channel still match.
  @Test
  func `new-node rename then three TEST Hello echoes attach`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let sendTimestamp = UInt32(Date().timeIntervalSince1970)
    let messageID = UUID()
    try await store.saveMessage(MessageDTO.testChannelMessage(
      id: messageID,
      radioID: radioID,
      channelIndex: channelIndex,
      text: "Hello",
      timestamp: sendTimestamp
    ))
    await service.configure(radioID: radioID)

    var lastCount: Int?
    for _ in 0..<3 {
      let echo = makeEcho(
        radioID: radioID,
        channelIndex: channelIndex,
        senderTimestamp: sendTimestamp,
        body: "Hello",
        senderName: "TEST"
      )
      lastCount = await service.processForRepeats(echo)
    }

    #expect(lastCount == 3)
    let repeats = try await store.fetchMessageRepeats(messageID: messageID)
    #expect(repeats.count == 3)
  }

  // MARK: - Incoming extra paths

  @Test
  func `incoming channel RX with a different path inserts a repeat`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let wireTimestamp: UInt32 = 1_704_067_200
    let message = incomingChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "flood copy",
      senderName: Self.testNodeName,
      wireTimestamp: wireTimestamp,
      pathNodes: Data([0xAA]),
      pathLength: 1
    )
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)

    let extra = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "flood copy",
      pathNodes: [0xBB]
    )
    let count = await service.processForRepeats(extra)

    #expect(count == 1)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.count == 1)
    #expect(repeats.first?.pathNodes == Data([0xBB]))
    let updated = try await store.fetchMessage(id: message.id)
    #expect(updated?.heardRepeats == 1)
  }

  @Test
  func `incoming RX whose path equals message pathNodes inserts nothing`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let wireTimestamp: UInt32 = 1_704_067_201
    let canonical = Data([0xAA])
    let message = incomingChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "same path",
      senderName: Self.testNodeName,
      wireTimestamp: wireTimestamp,
      pathNodes: canonical,
      pathLength: 1
    )
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)

    let echo = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "same path",
      pathNodes: [0xAA]
    )
    #expect(await service.processForRepeats(echo) == nil)
    #expect(try await store.fetchMessageRepeats(messageID: message.id).isEmpty)
    #expect(try await store.fetchMessage(id: message.id)?.heardRepeats == 0)
  }

  @Test
  func `second incoming RX with the same extra path inserts nothing`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let wireTimestamp: UInt32 = 1_704_067_202
    let message = incomingChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "collapse",
      senderName: Self.testNodeName,
      wireTimestamp: wireTimestamp,
      pathNodes: Data([0xAA]),
      pathLength: 1
    )
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)

    let first = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "collapse",
      pathNodes: [0xBB]
    )
    let second = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "collapse",
      pathNodes: [0xBB]
    )
    #expect(await service.processForRepeats(first) == 1)
    #expect(await service.processForRepeats(second) == nil)
    #expect(try await store.fetchMessageRepeats(messageID: message.id).count == 1)
  }

  @Test
  func `clock-corrected incoming message still joins extra RX by wire timestamp`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 1
    let wireTimestamp: UInt32 = 100
    let receiveTime = Date(timeIntervalSince1970: 1_704_067_200)
    let message = incomingChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "skewed clock",
      senderName: Self.testNodeName,
      wireTimestamp: wireTimestamp,
      pathNodes: Data([0xAA]),
      pathLength: 1,
      timestampCorrected: true,
      receiveTime: receiveTime
    )
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)

    let extra = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "skewed clock",
      pathNodes: [0xCC]
    )
    #expect(await service.processForRepeats(extra) == 1)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.first?.pathNodes == Data([0xCC]))
  }

  @Test
  func `empty path extra is recorded as a 0-hop arrival`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let wireTimestamp: UInt32 = 1_704_067_203
    let message = incomingChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "zero hop extra",
      senderName: Self.testNodeName,
      wireTimestamp: wireTimestamp,
      pathNodes: Data([0xAA]),
      pathLength: 1
    )
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)

    let extra = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "zero hop extra",
      pathNodes: [],
      pathLength: 0
    )
    #expect(await service.processForRepeats(extra) == 1)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.first?.pathNodes == Data())
    #expect(repeats.first?.pathLength == 0)
  }

  @Test
  func `harvest after save records extras from RX rows that arrived first`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let wireTimestamp: UInt32 = 1_704_067_204
    let earlier = Date(timeIntervalSince1970: 1_700_000_000)
    let later = earlier.addingTimeInterval(1)
    let pathA: [UInt8] = [0xA1]
    let pathB: [UInt8] = [0xB2]

    let rxA = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "harvest me",
      pathNodes: pathA,
      receivedAt: earlier
    )
    let rxB = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "harvest me",
      pathNodes: pathB,
      receivedAt: later
    )
    await service.configure(radioID: radioID)

    #expect(await service.processForRepeats(rxA) == nil)
    #expect(await service.processForRepeats(rxB) == nil)

    let message = incomingChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "harvest me",
      senderName: Self.testNodeName,
      wireTimestamp: wireTimestamp,
      pathNodes: Data(pathB),
      pathLength: 1,
      receiveTime: later
    )
    try await store.saveMessage(message)
    await service.harvestIncomingPaths(for: message, decodedCandidates: [rxA, rxB])

    let updated = try await store.fetchMessage(id: message.id)
    #expect(updated?.heardRepeats == 1)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.count == 1)
    #expect(repeats.first?.pathNodes == Data(pathA))
  }

  @Test
  func `harvest ignores a same-stamp 0x88 whose body does not match`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let stamp: UInt32 = 42
    let pathA: [UInt8] = [0xA1]
    let pathB: [UInt8] = [0xB2]
    let canonical = Data([0xC0])

    let aliceRX = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: stamp,
      body: "one",
      senderName: "Alice",
      pathNodes: pathA
    )
    let bobRX = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: stamp,
      body: "two",
      senderName: "Bob",
      pathNodes: pathB
    )
    let message = incomingChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "one",
      senderName: "Alice",
      wireTimestamp: stamp,
      pathNodes: canonical,
      pathLength: 1
    )
    try await store.saveMessage(message)
    await service.harvestIncomingPaths(
      for: message,
      decodedCandidates: [aliceRX, bobRX]
    )

    let updated = try await store.fetchMessage(id: message.id)
    #expect(updated?.heardRepeats == 1)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.count == 1)
    #expect(repeats.first?.pathNodes == Data(pathA))
    #expect(repeats.first?.rxLogEntryID == aliceRX.id)
    #expect(try await store.messageRepeatExists(rxLogEntryID: bobRX.id) == false)
  }

  @Test
  func `harvest adopts a matching path onto an unknown incoming message`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let wireTimestamp: UInt32 = 1_704_067_205
    let message = incomingChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "adopt me",
      senderName: Self.testNodeName,
      wireTimestamp: wireTimestamp,
      pathNodes: nil,
      pathLength: 0
    )
    try await store.saveMessage(message)

    let rx = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "adopt me",
      pathNodes: [0xAA]
    )
    await service.harvestIncomingPaths(for: message, decodedCandidates: [rx])

    let updated = try await store.fetchMessage(id: message.id)
    #expect(updated?.pathNodes == Data([0xAA]))
    #expect(updated?.pathLength == 1)
    #expect(updated?.heardRepeats == 0)
    #expect(try await store.fetchMessageRepeats(messageID: message.id).isEmpty)
  }

  @Test
  func `harvest adopts the first matching path and records the later extra`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    let wireTimestamp: UInt32 = 1_704_067_206
    let earlier = Date(timeIntervalSince1970: 1_700_000_000)
    let later = earlier.addingTimeInterval(1)
    let message = incomingChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "two paths",
      senderName: Self.testNodeName,
      wireTimestamp: wireTimestamp,
      pathNodes: nil,
      pathLength: 0
    )
    try await store.saveMessage(message)

    let first = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "two paths",
      pathNodes: [0xAA],
      receivedAt: earlier
    )
    let second = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: wireTimestamp,
      body: "two paths",
      pathNodes: [0xBB],
      receivedAt: later
    )
    await service.harvestIncomingPaths(for: message, decodedCandidates: [second, first])

    let updated = try await store.fetchMessage(id: message.id)
    #expect(updated?.pathNodes == Data([0xAA]))
    #expect(updated?.pathLength == 1)
    #expect(updated?.heardRepeats == 1)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.count == 1)
    #expect(repeats.first?.pathNodes == Data([0xBB]))
  }

  // MARK: - Datagram paths

  /// Canonical `im4:` text: what a channel datagram row stores in both directions.
  private let imageText = (try? MCOImageCodec.text(
    fromAppPayload: MCOImageCodec.channelAppPayload(fromText: "im3:g/5WjAL`NA")
  )) ?? ""
  private let imageSender = "Ada"

  private func imageBlob() throws -> Data {
    try MCOImageTransport.dataField(text: imageText, senderName: imageSender)
  }

  private func channelMCOImage(
    id: UUID = UUID(),
    radioID: UUID,
    channelIndex: UInt8,
    direction: MessageDirection,
    senderTimestamp: UInt32?,
    deduplicationKey: String?,
    pathLength: UInt8 = 4,
    snr: Double? = 9,
    // Near now: RX echoes are logged at the current time and must fall in the datagram match window.
    timestamp: UInt32 = UInt32(Date().timeIntervalSince1970)
  ) -> MessageDTO {
    var message = MessageDTO.testChannelMessage(
      id: id,
      radioID: radioID,
      channelIndex: channelIndex,
      text: imageText,
      timestamp: timestamp,
      direction: direction,
      pathLength: pathLength,
      snr: snr,
      senderNodeName: imageSender
    )
    message.senderTimestamp = senderTimestamp
    message.deduplicationKey = deduplicationKey
    message.pathNodes = nil
    return message
  }

  private func makeDatagramEcho(
    radioID: UUID,
    channelIndex: UInt8,
    blob: Data,
    id: UUID = UUID(),
    pathNodes: [UInt8] = [0x42],
    pathLength: UInt8 = 1,
    receivedAt: Date = Date(),
    routeType: RouteType = .flood,
    regionScope: String? = nil,
    dataType: UInt16 = MCOImageTransport.applicationDataType
  ) -> RxLogEntryDTO {
    let parsed = ParsedRxLogData(
      snr: 1.0,
      rssi: -80,
      rawPayload: Data([0x01]),
      routeType: routeType,
      payloadType: .groupData,
      payloadVersion: 0,
      payloadTypeBits: 6,
      transportCode: nil,
      pathLength: pathLength,
      pathNodes: pathNodes,
      packetPayload: Data([0x01])
    )
    return RxLogEntryDTO(
      id: id,
      radioID: radioID,
      receivedAt: receivedAt,
      from: parsed,
      channelIndex: channelIndex,
      decryptStatus: .success,
      regionScope: regionScope,
      datagramDataType: dataType,
      datagramData: blob
    )
  }

  @Test
  func `one outgoing channel MCOimg and one RX row count a single echo`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 1
    let blob = try imageBlob()
    let message = channelMCOImage(
      radioID: radioID,
      channelIndex: channelIndex,
      direction: .outgoing,
      senderTimestamp: nil,
      deduplicationKey: nil
    )
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)

    let echo = makeDatagramEcho(radioID: radioID, channelIndex: channelIndex, blob: blob)
    let count = await service.processForRepeats(echo)
    let again = await service.processForRepeats(echo)
    let other = await service.processForRepeats(makeDatagramEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      blob: Data([0x00])
    ))

    #expect(count == 1)
    #expect(again == nil)
    #expect(other == nil)
    let updated = try #require(await store.fetchMessage(id: message.id))
    #expect(updated.heardRepeats == 1)
    #expect(updated.snr == 9)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.count == 1)
    #expect(repeats.first?.rxLogEntryID == echo.id)
  }

  @Test
  func `an incoming extra path increments and an empty path is one arrival`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 2
    let blob = try imageBlob()
    let message = channelMCOImage(
      radioID: radioID,
      channelIndex: channelIndex,
      direction: .incoming,
      senderTimestamp: 0,
      deduplicationKey: "ch-2-mcoimg"
    )
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)

    let empty = makeDatagramEcho(
      radioID: radioID, channelIndex: channelIndex, blob: blob, pathNodes: [], pathLength: 0
    )
    _ = await service.processForRepeats(empty)
    _ = await service.processForRepeats(makeDatagramEcho(
      radioID: radioID, channelIndex: channelIndex, blob: blob, pathNodes: [], pathLength: 0
    ))
    var afterEmpty = try #require(await store.fetchMessage(id: message.id))
    #expect(afterEmpty.pathNodes == Data())
    #expect(afterEmpty.pathLength == 0)
    #expect(afterEmpty.heardRepeats == 0)
    #expect(afterEmpty.senderTimestamp == 0)
    #expect(afterEmpty.snr == 9)

    _ = await service.processForRepeats(makeDatagramEcho(
      radioID: radioID, channelIndex: channelIndex, blob: blob, pathNodes: [0x21], pathLength: 1
    ))
    afterEmpty = try #require(await store.fetchMessage(id: message.id))
    #expect(afterEmpty.heardRepeats == 1)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.count == 1)
    #expect(repeats.first?.pathNodes == Data([0x21]))
  }

  @Test
  func `the earliest datagram RX row becomes the canonical path`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let blob = try imageBlob()
    let message = channelMCOImage(
      radioID: radioID, channelIndex: 3, direction: .incoming,
      senderTimestamp: 0, deduplicationKey: "ch-3-mcoimg"
    )
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)
    let earlier = Date(timeIntervalSince1970: 1_700_000_100)
    let later = earlier.addingTimeInterval(2)
    let first = makeDatagramEcho(
      radioID: radioID, channelIndex: 3, blob: blob,
      pathNodes: [0xAA], pathLength: 1, receivedAt: earlier, routeType: .flood, regionScope: "Ridge"
    )
    let second = makeDatagramEcho(
      radioID: radioID, channelIndex: 3, blob: blob,
      pathNodes: [0xBB], pathLength: 2, receivedAt: later
    )
    await service.harvestDatagramPaths(for: message, decodedCandidates: [second, first])

    let updated = try #require(await store.fetchMessage(id: message.id))
    #expect(updated.pathNodes == Data([0xAA]))
    #expect(updated.pathLength == 1)
    #expect(updated.routeType == .flood)
    #expect(updated.regionScope == "Ridge")
    #expect(updated.heardRepeats == 1)
    #expect(updated.senderTimestamp == 0)
    #expect(updated.snr == 9)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.map(\.rxLogEntryID) == [second.id])
  }

  @Test
  func `group text with wire time 0 does not take the datagram path`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 4
    let blob = try imageBlob()
    let key = DeduplicationKey.contentBased(
      contactID: nil,
      channelIndex: channelIndex,
      senderNodeName: imageSender,
      timestamp: 0,
      content: imageText
    )
    var message = channelMCOImage(
      radioID: radioID,
      channelIndex: channelIndex,
      direction: .incoming,
      senderTimestamp: 0,
      deduplicationKey: key,
      pathLength: 7
    )
    message.pathNodes = Data([0x99])
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)

    let count = await service.processForRepeats(makeDatagramEcho(
      radioID: radioID, channelIndex: channelIndex, blob: blob, pathNodes: [0x11]
    ))
    #expect(count == nil)
    let updated = try #require(await store.fetchMessage(id: message.id))
    #expect(updated.pathNodes == Data([0x99]))
    #expect(updated.pathLength == 7)
    #expect(updated.heardRepeats == 0)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.isEmpty)
  }

  @Test
  func `a later datagram region updates the MCOimg and a timestamp 0 write does not`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let channelIndex: UInt8 = 5
    let blob = try imageBlob()
    let message = channelMCOImage(
      radioID: radioID, channelIndex: channelIndex, direction: .incoming,
      senderTimestamp: 0, deduplicationKey: "ch-5-mcoimg"
    )
    try await store.saveMessage(message)
    var text = MessageDTO.testChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "plain",
      timestamp: 50,
      direction: .incoming,
      senderNodeName: "Ada"
    )
    text.senderTimestamp = 0
    text.deduplicationKey = DeduplicationKey.contentBased(
      contactID: nil, channelIndex: channelIndex, senderNodeName: "Ada", timestamp: 0, content: "plain"
    )
    try await store.saveMessage(text)

    let echo = makeDatagramEcho(radioID: radioID, channelIndex: channelIndex, blob: blob, pathNodes: [0x01])
    await service.copyDatagramRegionIfCanonical(
      entry: echo,
      regionScope: "Ridge",
      regionScopeMatches: ["Ridge"],
      channelEntries: [echo]
    )
    var updated = try #require(await store.fetchMessage(id: message.id))
    #expect(updated.regionScope == "Ridge")

    _ = try await store.batchUpdateChannelMessageRegion(
      radioID: radioID,
      updates: [(channelIndex: channelIndex, senderTimestamp: 0, regionScope: "South", regionScopeMatches: ["South"])]
    )
    updated = try #require(await store.fetchMessage(id: message.id))
    let textRow = try #require(await store.fetchMessage(id: text.id))
    #expect(updated.regionScope == "Ridge")
    #expect(textRow.regionScope == "South")
  }

  @Test
  func `processForRepeats skips DB lookups for unmatched packets and hits after note`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let spy = CountingHeardRepeatStore(wrapping: store)
    let service = HeardRepeatsService(dataStore: spy)
    let radioID = UUID()
    let channelIndex: UInt8 = 0
    await service.configure(radioID: radioID)

    let unmatched = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: 1_704_067_500,
      body: "unmatched"
    )
    #expect(await service.processForRepeats(unmatched) == nil)
    let initialSentCalls = await spy.findSentChannelMessageCalls
    let initialDedupCalls = await spy.fetchMessageByDedupKeyCalls
    #expect(initialSentCalls == 0)
    #expect(initialDedupCalls == 0)

    let sentTimestamp: UInt32 = 1_704_067_501
    let sentMessage = MessageDTO.testChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "echo me",
      timestamp: sentTimestamp
    )
    try await store.saveMessage(sentMessage)
    await service.noteSentChannelMessage(channelIndex: channelIndex, timestamp: sentTimestamp)

    let echo = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: sentTimestamp,
      body: "echo me"
    )
    #expect(await service.processForRepeats(echo) == 1)
    let afterEchoSentCalls = await spy.findSentChannelMessageCalls
    let afterEchoDedupCalls = await spy.fetchMessageByDedupKeyCalls
    #expect(afterEchoSentCalls == 1)
    #expect(afterEchoDedupCalls == 0)

    let incomingTimestamp: UInt32 = 1_704_067_502
    let incoming = incomingChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "extra arrival",
      senderName: Self.testNodeName,
      wireTimestamp: incomingTimestamp,
      pathNodes: Data([0xAA]),
      pathLength: 1
    )
    try await store.saveMessage(incoming)
    await service.noteIncomingChannelMessage(deduplicationKey: incoming.deduplicationKey)

    let extra = makeEcho(
      radioID: radioID,
      channelIndex: channelIndex,
      senderTimestamp: incomingTimestamp,
      body: "extra arrival",
      pathNodes: [0xBB]
    )
    #expect(await service.processForRepeats(extra) == 1)
    let afterExtraDedupCalls = await spy.fetchMessageByDedupKeyCalls
    #expect(afterExtraDedupCalls == 1)
  }
}

private actor CountingHeardRepeatStore: HeardRepeatPersisting {
  private let inner: PersistenceStore
  private(set) var findSentChannelMessageCalls = 0
  private(set) var fetchMessageByDedupKeyCalls = 0

  init(wrapping inner: PersistenceStore) {
    self.inner = inner
  }

  func fetchRecentSentChannelMessageKeys(radioID: UUID, limit: Int) async throws -> [SentChannelMessageKey]? {
    try await inner.fetchRecentSentChannelMessageKeys(radioID: radioID, limit: limit)
  }

  func fetchRecentIncomingChannelDeduplicationKeys(radioID: UUID, limit: Int) async throws -> [String]? {
    try await inner.fetchRecentIncomingChannelDeduplicationKeys(radioID: radioID, limit: limit)
  }

  func findSentChannelMessage(
    radioID: UUID,
    channelIndex: UInt8,
    timestamp: UInt32,
    text: String
  ) async throws -> MessageDTO? {
    findSentChannelMessageCalls += 1
    return try await inner.findSentChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      timestamp: timestamp,
      text: text
    )
  }

  func fetchMessage(deduplicationKey: String, radioID: UUID) async throws -> MessageDTO? {
    fetchMessageByDedupKeyCalls += 1
    return try await inner.fetchMessage(deduplicationKey: deduplicationKey, radioID: radioID)
  }

  func saveMessageRepeat(_ dto: MessageRepeatDTO) async throws {
    try await inner.saveMessageRepeat(dto)
  }

  func fetchMessageRepeats(messageID: UUID) async throws -> [MessageRepeatDTO] {
    try await inner.fetchMessageRepeats(messageID: messageID)
  }

  func incrementMessageHeardRepeats(id: UUID) async throws -> Int {
    try await inner.incrementMessageHeardRepeats(id: id)
  }

  func admitIncomingPath(
    messageID: UUID,
    pathNodes: Data,
    pathLength: UInt8,
    snr: Double?,
    rssi: Int?,
    receivedAt: Date,
    rxLogEntryID: UUID?
  ) async throws -> IncomingPathAdmission {
    try await inner.admitIncomingPath(
      messageID: messageID,
      pathNodes: pathNodes,
      pathLength: pathLength,
      snr: snr,
      rssi: rssi,
      receivedAt: receivedAt,
      rxLogEntryID: rxLogEntryID
    )
  }

  func admitSentEcho(
    messageID: UUID,
    expectedTimestamp: UInt32,
    receivedAt: Date,
    pathNodes: Data,
    pathLength: UInt8,
    snr: Double?,
    rssi: Int?,
    rxLogEntryID: UUID?
  ) async throws -> Int? {
    try await inner.admitSentEcho(
      messageID: messageID,
      expectedTimestamp: expectedTimestamp,
      receivedAt: receivedAt,
      pathNodes: pathNodes,
      pathLength: pathLength,
      snr: snr,
      rssi: rssi,
      rxLogEntryID: rxLogEntryID
    )
  }

  func adoptIncomingPathIfUnknown(id: UUID, pathNodes: Data, pathLength: UInt8) async throws -> Bool {
    try await inner.adoptIncomingPathIfUnknown(id: id, pathNodes: pathNodes, pathLength: pathLength)
  }

  func incrementMessageSendCount(id: UUID) async throws -> Int {
    try await inner.incrementMessageSendCount(id: id)
  }
}
