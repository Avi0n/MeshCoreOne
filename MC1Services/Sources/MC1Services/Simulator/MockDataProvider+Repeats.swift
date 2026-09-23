import Foundation

extension MockDataProvider {
  /// Message IDs that carry seeded `MessageRepeat` rows.
  /// Outgoing rows are send echoes. Incoming rows are later flood routes.
  static let messagesWithRepeats: [UUID] = [
    frankRepeatMessageID,
    aliceMultiPathMessageID,
    publicMultiPathMessageID
  ]

  /// Distinct repeater hashes, hop counts, and signal stats.
  /// The row count matches the parent message's `heardRepeats`.
  static func messageRepeats(for messageID: UUID) -> [MessageRepeatDTO] {
    if messageID == aliceMultiPathMessageID {
      return incomingPathRepeats(
        for: messageID,
        receivedAt: Date().addingTimeInterval(aliceMultiPathAge)
      )
    }
    if messageID == publicMultiPathMessageID {
      return incomingPathRepeats(
        for: messageID,
        receivedAt: Date().addingTimeInterval(publicMultiPathAge)
      )
    }
    guard messageID == frankRepeatMessageID else { return [] }
    let now = Date()
    return [
      MessageRepeatDTO(
        id: UUID(uuidString: "B0000000-0000-0000-0000-000000000001")!,
        messageID: messageID,
        receivedAt: now.addingTimeInterval(-255_590),
        pathNodes: Data([0x31]), // 1 hop, 1-byte hash
        pathLength: encodePathLen(hashSize: 1, hopCount: 1),
        snr: 6.5,
        rssi: -92,
        rxLogEntryID: nil
      ),
      MessageRepeatDTO(
        id: UUID(uuidString: "B0000000-0000-0000-0000-000000000002")!,
        messageID: messageID,
        receivedAt: now.addingTimeInterval(-255_585),
        pathNodes: Data([0x8F, 0x2C]), // 1 hop, 2-byte hash
        pathLength: encodePathLen(hashSize: 2, hopCount: 1),
        snr: 4.2,
        rssi: -101,
        rxLogEntryID: nil
      ),
      MessageRepeatDTO(
        id: UUID(uuidString: "B0000000-0000-0000-0000-000000000003")!,
        messageID: messageID,
        receivedAt: now.addingTimeInterval(-255_580),
        pathNodes: Data([0x44, 0x71]), // 2 hops, 1-byte hash
        pathLength: encodePathLen(hashSize: 1, hopCount: 2),
        snr: 1.9,
        rssi: -108,
        rxLogEntryID: nil
      )
    ]
  }
}
