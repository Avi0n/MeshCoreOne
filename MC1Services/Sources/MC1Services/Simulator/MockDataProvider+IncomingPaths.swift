import Foundation

extension MockDataProvider {
  /// Stored path on the multi-path incoming messages. Later routes are `messageRepeats(for:)`.
  static let incomingFirstPath = Data([northRidgeRepeaterSeed, twinPeaksRepeaterSeed])
  static let incomingExtraPathCount = 3

  static let aliceMultiPathAge: TimeInterval = -40
  static let publicMultiPathAge: TimeInterval = -20

  static func aliceMultiPathMessage(now: Date, senderKey: Data) -> MessageDTO {
    MockMessageFactory.message(
      id: aliceMultiPathMessageID,
      createdAt: now.addingTimeInterval(aliceMultiPathAge),
      text: "Still on the trail. This one came in a few different ways.",
      direction: .incoming,
      contactID: aliceChenID,
      pathLength: encodePathLen(hashSize: 1, hopCount: 2),
      snr: 9.1,
      pathNodes: incomingFirstPath,
      senderKeyPrefix: senderKey,
      isRead: false,
      heardRepeats: incomingExtraPathCount,
      routeType: .flood
    )
  }

  static func publicMultiPathMessage(now: Date) -> MessageDTO {
    MockMessageFactory.message(
      id: publicMultiPathMessageID,
      createdAt: now.addingTimeInterval(publicMultiPathAge),
      text: "Net check from the ridge. Hearing a few routes into the city.",
      direction: .incoming,
      channelIndex: publicChannelIndex,
      pathLength: encodePathLen(hashSize: 1, hopCount: 2),
      snr: 8.4,
      pathNodes: incomingFirstPath,
      senderNodeName: "Alice Chen",
      isRead: false,
      heardRepeats: incomingExtraPathCount,
      routeType: .flood
    )
  }

  /// Later hearings of the same flood. Each route has its own hop list and SNR.
  static func incomingPathRepeats(for messageID: UUID, receivedAt: Date) -> [MessageRepeatDTO] {
    let ids = repeatIDs(for: messageID)
    guard ids.count == incomingExtraRoutes.count else { return [] }
    return zip(ids, incomingExtraRoutes).map { id, route in
      MessageRepeatDTO(
        id: id,
        messageID: messageID,
        receivedAt: receivedAt.addingTimeInterval(route.offset),
        pathNodes: route.pathNodes,
        pathLength: encodePathLen(hashSize: 1, hopCount: route.hopCount),
        snr: route.snr,
        rssi: route.rssi,
        rxLogEntryID: nil
      )
    }
  }

  private struct ExtraRoute {
    let offset: TimeInterval
    let pathNodes: Data
    let hopCount: Int
    let snr: Double
    let rssi: Int
  }

  private static let incomingExtraRoutes: [ExtraRoute] = [
    ExtraRoute(
      offset: 4,
      pathNodes: Data([oaklandRepeaterSeed]),
      hopCount: 1,
      snr: 5.4,
      rssi: -104
    ),
    ExtraRoute(
      offset: 12,
      pathNodes: Data([twinPeaksRepeaterSeed, oaklandRepeaterSeed]),
      hopCount: 2,
      snr: 3.1,
      rssi: -111
    ),
    ExtraRoute(
      offset: 28,
      pathNodes: Data([northRidgeRepeaterSeed, twinPeaksRepeaterSeed, oaklandRepeaterSeed]),
      hopCount: 3,
      snr: 1.6,
      rssi: -116
    )
  ]

  private static func repeatIDs(for messageID: UUID) -> [UUID] {
    switch messageID {
    case aliceMultiPathMessageID:
      [
        UUID(uuidString: "B1000000-0000-0000-0000-000000000001")!,
        UUID(uuidString: "B1000000-0000-0000-0000-000000000002")!,
        UUID(uuidString: "B1000000-0000-0000-0000-000000000003")!
      ]
    case publicMultiPathMessageID:
      [
        UUID(uuidString: "B2000000-0000-0000-0000-000000000001")!,
        UUID(uuidString: "B2000000-0000-0000-0000-000000000002")!,
        UUID(uuidString: "B2000000-0000-0000-0000-000000000003")!
      ]
    default:
      []
    }
  }
}
