import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Heard repeat snapshot writes", .timeLimit(.minutes(1)))
struct HeardRepeatSnapshotWriteTests {
  @Test
  func `a stale nil canonical re-reads the current path and records the second arrival`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let message = incomingMessage(radioID: radioID, pathNodes: nil, pathLength: 0)
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)
    let stale = try #require(try await store.fetchMessage(id: message.id))
    #expect(stale.pathNodes == nil)

    let first = Data([0x11])
    let second = Data([0x22])
    _ = await service.recordDistinctPathIfNeeded(
      message: stale,
      pathNodes: first,
      pathLength: 1,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
      rxLogEntryID: UUID()
    )
    _ = await service.recordDistinctPathIfNeeded(
      message: stale,
      pathNodes: second,
      pathLength: 1,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_001),
      rxLogEntryID: UUID()
    )

    let stored = try #require(try await store.fetchMessage(id: message.id))
    #expect(stored.pathNodes == first)
    #expect(stored.pathLength == 1)
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.map(\.pathNodes) == [second])
    #expect(stored.heardRepeats == repeats.count)
  }

  @Test
  func `an empty path is a 0-hop arrival and a nil path is unknown`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    await service.configure(radioID: radioID)

    let unknown = incomingMessage(radioID: radioID, pathNodes: nil, pathLength: 0)
    try await store.saveMessage(unknown)
    _ = await service.recordDistinctPathIfNeeded(
      message: unknown,
      pathNodes: Data(),
      pathLength: 0,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_010),
      rxLogEntryID: UUID()
    )
    let adopted = try #require(try await store.fetchMessage(id: unknown.id))
    #expect(adopted.pathNodes == Data())
    #expect(adopted.pathLength == 0)
    #expect(try await store.fetchMessageRepeats(messageID: unknown.id).isEmpty)
    #expect(adopted.heardRepeats == 0)

    let known = incomingMessage(radioID: radioID, pathNodes: Data([0x11]), pathLength: 1)
    try await store.saveMessage(known)
    _ = await service.recordDistinctPathIfNeeded(
      message: known,
      pathNodes: Data(),
      pathLength: 0,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_011),
      rxLogEntryID: UUID()
    )
    let stored = try #require(try await store.fetchMessage(id: known.id))
    let repeats = try await store.fetchMessageRepeats(messageID: known.id)
    #expect(stored.pathNodes == Data([0x11]))
    #expect(repeats.map(\.pathNodes) == [Data()])
    #expect(stored.heardRepeats == repeats.count)
  }

  @Test
  func `distinct paths are extras and a repeated path or RX entry is unchanged`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let message = incomingMessage(radioID: radioID, pathNodes: Data([0x01]), pathLength: 1)
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)

    let firstRX = UUID()
    let secondRX = UUID()
    _ = await service.recordDistinctPathIfNeeded(
      message: message,
      pathNodes: Data([0x22]),
      pathLength: 1,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_020),
      rxLogEntryID: firstRX
    )
    _ = await service.recordDistinctPathIfNeeded(
      message: message,
      pathNodes: Data([0x33]),
      pathLength: 1,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_021),
      rxLogEntryID: secondRX
    )
    _ = await service.recordDistinctPathIfNeeded(
      message: message,
      pathNodes: Data([0x22]),
      pathLength: 1,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_022),
      rxLogEntryID: UUID()
    )
    _ = await service.recordDistinctPathIfNeeded(
      message: message,
      pathNodes: Data([0x44]),
      pathLength: 1,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_023),
      rxLogEntryID: firstRX
    )

    let stored = try #require(try await store.fetchMessage(id: message.id))
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.map(\.pathNodes) == [Data([0x22]), Data([0x33])])
    #expect(stored.heardRepeats == repeats.count)
    #expect(stored.pathNodes == Data([0x01]))
  }

  @Test
  func `a missing parent records nothing`() async throws {
    let (store, service) = try makeStoreAndService()
    let message = incomingMessage(radioID: UUID(), pathNodes: nil, pathLength: 0)
    let recorded = await service.recordDistinctPathIfNeeded(
      message: message,
      pathNodes: Data([0x22]),
      pathLength: 1,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_030),
      rxLogEntryID: UUID()
    )
    #expect(recorded == nil)
    #expect(try await store.fetchMessage(id: message.id) == nil)
    #expect(try await store.fetchMessageRepeats(messageID: message.id).isEmpty)
  }

  @Test
  func `sent echoes with the same path and different RX ids stay separate`() async throws {
    let (store, _) = try makeStoreAndService()
    var message = incomingMessage(radioID: UUID(), pathNodes: Data([0x01]), pathLength: 1)
    message.direction = .outgoing
    try await store.saveMessage(message)
    let firstRX = UUID()
    let secondRX = UUID()
    let first = try await store.admitSentEcho(
      messageID: message.id,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_040),
      pathNodes: Data([0x11]),
      pathLength: 1,
      snr: nil,
      rssi: nil,
      rxLogEntryID: firstRX
    )
    let second = try await store.admitSentEcho(
      messageID: message.id,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_041),
      pathNodes: Data([0x11]),
      pathLength: 1,
      snr: nil,
      rssi: nil,
      rxLogEntryID: secondRX
    )
    let sameRX = try await store.admitSentEcho(
      messageID: message.id,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_042),
      pathNodes: Data([0x22]),
      pathLength: 1,
      snr: nil,
      rssi: nil,
      rxLogEntryID: firstRX
    )

    let stored = try #require(try await store.fetchMessage(id: message.id))
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(first == 1)
    #expect(second == 2)
    #expect(sameRX == nil)
    #expect(repeats.map(\.pathNodes) == [Data([0x11]), Data([0x11])])
    #expect(stored.heardRepeats == repeats.count)
  }

  @Test
  func `concurrent same-path admissions record one extra`() async throws {
    let (store, service) = try makeStoreAndService()
    let radioID = UUID()
    let message = incomingMessage(radioID: radioID, pathNodes: Data([0x01]), pathLength: 1)
    try await store.saveMessage(message)
    await service.configure(radioID: radioID)
    let barrier = ArrivalBarrier(2)
    await service.setAdmissionHoldForTesting {
      await barrier.arrive()
    }

    async let first = service.recordDistinctPathIfNeeded(
      message: message,
      pathNodes: Data([0x22]),
      pathLength: 1,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_050),
      rxLogEntryID: UUID()
    )
    async let second = service.recordDistinctPathIfNeeded(
      message: message,
      pathNodes: Data([0x22]),
      pathLength: 1,
      snr: nil,
      rssi: nil,
      receivedAt: Date(timeIntervalSince1970: 1_700_000_051),
      rxLogEntryID: UUID()
    )
    _ = await (first, second)

    let stored = try #require(try await store.fetchMessage(id: message.id))
    let repeats = try await store.fetchMessageRepeats(messageID: message.id)
    #expect(repeats.map(\.pathNodes) == [Data([0x22])])
    #expect(stored.heardRepeats == repeats.count)
  }

  private func makeStoreAndService() throws -> (PersistenceStore, HeardRepeatsService) {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    return (store, HeardRepeatsService(dataStore: store))
  }

  private func incomingMessage(radioID: UUID, pathNodes: Data?, pathLength: UInt8) -> MessageDTO {
    var message = MessageDTO.testChannelMessage(
      radioID: radioID,
      channelIndex: 0,
      text: "arrival",
      timestamp: 1_704_067_200,
      direction: .incoming,
      pathLength: pathLength,
      senderNodeName: "Ada"
    )
    message.pathNodes = pathNodes
    message.deduplicationKey = DeduplicationKey.contentBased(
      contactID: nil,
      channelIndex: 0,
      senderNodeName: "Ada",
      timestamp: 1_704_067_200,
      content: "arrival"
    )
    return message
  }
}

private actor ArrivalBarrier {
  private let expected: Int
  private var arrived = 0
  private var waiters: [CheckedContinuation<Void, Never>] = []

  init(_ expected: Int) {
    self.expected = expected
  }

  func arrive() async {
    arrived += 1
    if arrived >= expected {
      let pending = waiters
      waiters.removeAll()
      for waiter in pending {
        waiter.resume()
      }
      return
    }
    await withCheckedContinuation { waiters.append($0) }
  }
}
