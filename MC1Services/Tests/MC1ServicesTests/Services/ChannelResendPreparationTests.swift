import Foundation
@testable import MC1Services
import MeshCore
import SwiftData
import Testing

@Suite("Channel resend preparation")
struct ChannelResendPreparationTests {
  private static let originalTimestamp: UInt32 = 1_700_000_000
  private static let nextTimestamp: UInt32 = originalTimestamp + 60
  private static let previousPath = Data([0x11])
  private static let nextPath = Data([0x22])

  @Test
  func `an echo matched before preparation cannot enter the next wire packet's repeats`() async throws {
    let (store, message) = try await makeStoreAndMessage()
    let channelIndex = try #require(message.channelIndex)
    let stale = try #require(try await store.findSentChannelMessage(
      radioID: message.radioID, channelIndex: channelIndex,
      timestamp: Self.originalTimestamp, text: message.text
    ))
    try await store.prepareChannelMessageResend(id: message.id, timestamp: Self.nextTimestamp)
    let staleRX = UUID()
    let rejected = try await store.admitSentEcho(
      messageID: stale.id, expectedTimestamp: stale.timestamp,
      receivedAt: Date(), pathNodes: Self.previousPath, pathLength: 1,
      snr: nil, rssi: nil, rxLogEntryID: staleRX
    )
    #expect(rejected == nil)
    #expect(try await store.fetchMessageRepeats(messageID: message.id).isEmpty)
    #expect(try await store.fetchMessage(id: message.id)?.heardRepeats == 0)
    #expect(try await !store.messageRepeatExists(rxLogEntryID: staleRX))

    let accepted = try await store.admitSentEcho(
      messageID: message.id, expectedTimestamp: Self.nextTimestamp,
      receivedAt: Date(), pathNodes: Self.nextPath, pathLength: 1,
      snr: nil, rssi: nil, rxLogEntryID: UUID()
    )
    #expect(accepted == 1)
    #expect(try await store.fetchMessageRepeats(messageID: message.id).map(\.pathNodes) == [Self.nextPath])
    #expect(try await store.fetchMessage(id: message.id)?.heardRepeats == 1)
  }

  @Test
  @MainActor
  func `failed preparation rolls back timestamp and repeats while preserving buffered RX`() async throws {
    let (store, message) = try await makeStoreAndMessage()
    let originalRepeats = try await store.fetchMessageRepeats(messageID: message.id)
    let entry = RxLogEntryDTO(
      radioID: message.radioID,
      from: ParsedRxLogData(
        snr: 10, rssi: -65, rawPayload: Data([0x15]), routeType: .flood,
        payloadType: .groupText, payloadVersion: 0, payloadTypeBits: 5, transportCode: nil,
        pathLength: 0, pathNodes: [], packetPayload: Data([0xAB])
      )
    )
    try await store.bufferRxLogEntryForResendPreparation(entry)
    #expect(await store.unsavedRxLogInsertCount == 1)
    await store.setPrepareChannelMessageResendSaveFaultInjection { throw CocoaError(.fileWriteUnknown) }

    await #expect(throws: CocoaError.self) {
      try await store.prepareChannelMessageResend(id: message.id, timestamp: Self.nextTimestamp)
    }

    let stored = try #require(try await store.fetchMessage(id: message.id))
    #expect(stored.timestamp == message.timestamp)
    #expect(stored.heardRepeats == message.heardRepeats)
    #expect(try await store.fetchMessageRepeats(messageID: message.id) == originalRepeats)
    #expect(await store.unsavedRxLogInsertCount == 0)
    let persisted = ModelContext(store.modelContainer)
    #expect(try persisted.fetch(FetchDescriptor<Message>()).first?.timestamp == message.timestamp)
    #expect(try persisted.fetch(FetchDescriptor<MessageRepeat>()).map(\.id) == originalRepeats.map(\.id))
    #expect(try persisted.fetch(FetchDescriptor<RxLogEntry>()).map(\.id) == [entry.id])

    await store.setPrepareChannelMessageResendSaveFaultInjection(nil)
    try await store.prepareChannelMessageResend(id: message.id, timestamp: Self.nextTimestamp)
    #expect(try await store.fetchMessage(id: message.id)?.timestamp == Self.nextTimestamp)
    #expect(try await store.fetchMessageRepeats(messageID: message.id).isEmpty)
  }

  private func makeStoreAndMessage() async throws -> (PersistenceStore, MessageDTO) {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let message = MessageDTO.testChannelMessage(timestamp: Self.originalTimestamp, heardRepeats: 1)
    try await store.saveMessage(message)
    try await store.saveMessageRepeat(.testRepeat(messageID: message.id, pathNodes: Self.previousPath))
    return (store, message)
  }
}

extension PersistenceStore {
  func setPrepareChannelMessageResendSaveFaultInjection(_ hook: (@Sendable () throws -> Void)?) {
    prepareChannelMessageResendSaveFaultInjection = hook
  }
}

private extension PersistenceStore {
  func bufferRxLogEntryForResendPreparation(_ entry: RxLogEntryDTO) throws {
    try saveRxLogEntry(entry)
    rxLogFlushTask?.cancel()
    rxLogFlushTask = nil
  }
}
