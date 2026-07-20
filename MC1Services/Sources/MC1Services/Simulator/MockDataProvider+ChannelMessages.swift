import Foundation

extension MockDataProvider {
  /// Generate mock channel messages for a channel slot index. Channel messages
  /// carry `channelIndex` (not `contactID`); incoming rows populate `senderNodeName`
  /// and `senderKeyPrefix` for sender resolution.
  public static func channelMessages(for index: UInt8) -> [MessageDTO] {
    let now = Date()
    switch index {
    case publicChannelIndex: return publicChannelMessages(now: now)
    case bayAreaChannelIndex: return bayAreaChannelMessages(now: now)
    case trailCrewChannelIndex: return trailCrewChannelMessages(now: now)
    case meshHQChannelIndex: return meshHQChannelMessages(now: now)
    default: return []
    }
  }

  /// Mesh HQ: a long, multi-sender backlog. Its unread count exceeds one page
  /// (pageSize is 50), so the first-unread message — where the "New Messages"
  /// divider belongs — only loads when the initial page is sized to cover all
  /// unread. Exercises the jump-to-divider scroll and all-unread-in-one-page load.
  static let meshHQTotalMessages = 80
  static let meshHQUnreadCount = 60

  private static func meshHQChannelMessages(now: Date) -> [MessageDTO] {
    let path = encodePathLen(hashSize: 1, hopCount: 2)
    let senders: [(name: String, seed: UInt8)] = [
      ("Alice Chen", 10), ("Bob Martinez", 20), ("Carol Diaz", 90),
      ("Frank Wilson", 60), ("Hannah Lee", 80)
    ]
    let lines = [
      "Morning net check — who's on frequency?",
      "Copy, strong signal from the east ridge.",
      "Repeater 3 is back online after the firmware push.",
      "Battery bank held through the night, 82% remaining.",
      "Anyone have eyes on the weather coming over the pass?",
      "Rain expected this afternoon, plan accordingly.",
      "Trace route to the summit node looks clean, 3 hops.",
      "Lost the link to node 7 for a bit, back now.",
      "New antenna mount is up, gaining about 4 dB.",
      "Field team checking in from the trailhead.",
      "Packet loss down to under 2% since the reroute.",
      "Reminder: monthly maintenance window is Sunday.",
      "Great turnout on the group trace test today."
    ]

    return (0..<meshHQTotalMessages).map { i in
      let createdAt = now.addingTimeInterval(Double(-(meshHQTotalMessages - i) * 120))
      let isRead = i < (meshHQTotalMessages - meshHQUnreadCount)

      if i % 8 == 7 {
        return MockMessageFactory.message(
          id: meshHQMessageID(i),
          createdAt: createdAt,
          text: "Copy that, thanks for the update.",
          direction: .outgoing,
          status: .sent,
          channelIndex: meshHQChannelIndex,
          ackCode: UInt32(52000 + i),
          pathLength: path,
          isRead: isRead
        )
      }

      let sender = senders[i % senders.count]
      return MockMessageFactory.message(
        id: meshHQMessageID(i),
        createdAt: createdAt,
        text: lines[i % lines.count],
        direction: .incoming,
        channelIndex: meshHQChannelIndex,
        pathLength: path,
        snr: 6.5 + Double(i % 5) * 0.4,
        senderKeyPrefix: mockPublicKey(seed: sender.seed).prefix(6),
        senderNodeName: sender.name,
        isRead: isRead
      )
    }
  }

  /// Deterministic per-index UUID for the Mesh HQ backlog (`C3…` prefix).
  private static func meshHQMessageID(_ i: Int) -> UUID {
    UUID(uuidString: "C3000000-0000-0000-0000-\(String(format: "%012X", i))")!
  }

  /// Public: multi-sender chatter with a same-sender cluster, plus our own reply.
  private static func publicChannelMessages(now: Date) -> [MessageDTO] {
    let path = encodePathLen(hashSize: 1, hopCount: 2)
    return [
      channelIncoming(
        "C0000000-0000-0000-0000-000000000001",
        now.addingTimeInterval(-9000),
        "Anyone monitoring the north repeater today?",
        sender: "Alice Chen", keySeed: 10, snr: 7.4, path: path
      ),
      // Same-sender cluster (consecutive messages from Alice).
      channelIncoming(
        "C0000000-0000-0000-0000-000000000002",
        now.addingTimeInterval(-8940),
        "Signal's been solid on my end all morning.",
        sender: "Alice Chen", keySeed: 10, snr: 7.6, path: path
      ),
      channelIncoming(
        "C0000000-0000-0000-0000-000000000003",
        now.addingTimeInterval(-6000),
        "Same here, clear copy from the south ridge.",
        sender: "Bob Martinez", keySeed: 20, snr: 6.9, path: path
      ),
      MockMessageFactory.message(
        id: UUID(uuidString: "C0000000-0000-0000-0000-000000000004")!,
        createdAt: now.addingTimeInterval(-1200),
        text: "Good to hear. I'll run a trace this afternoon.",
        direction: .outgoing,
        status: .sent,
        channelIndex: publicChannelIndex,
        ackCode: 50001,
        pathLength: path
      )
    ]
  }

  /// Bay Area (favorite): a reacted message and a self-mention (unread mention badge).
  private static func bayAreaChannelMessages(now: Date) -> [MessageDTO] {
    let path = encodePathLen(hashSize: 1, hopCount: 1)
    return [
      channelIncoming(
        "C1000000-0000-0000-0000-000000000001",
        now.addingTimeInterval(-7200),
        "Welcome to all the new members this week!",
        sender: "Carol Diaz", keySeed: 90, snr: 9.1, path: path
      ),
      // Reacted message (badge applied via updateMessageReactionSummary).
      channelIncoming(
        "C1000000-0000-0000-0000-000000000002",
        now.addingTimeInterval(-5400),
        "We just passed 50 active nodes in the area 🎉",
        sender: "Carol Diaz", keySeed: 90, snr: 9.0, path: path
      ),
      // Self-mention drives the mention highlight and unread-mention badge; also reacted.
      channelIncoming(
        bayAreaMentionMessageID.uuidString,
        now.addingTimeInterval(-3600),
        "@[Sim] can you cover the cleanup this Saturday?",
        sender: "Carol Diaz", keySeed: 90, snr: 8.8, path: path,
        isRead: false, containsSelfMention: true, mentionSeen: false
      )
    ]
  }

  /// Trail Crew (muted): a short exchange to show a muted channel.
  private static func trailCrewChannelMessages(now: Date) -> [MessageDTO] {
    let path = encodePathLen(hashSize: 1, hopCount: 2)
    return [
      channelIncoming(
        "C2000000-0000-0000-0000-000000000001",
        now.addingTimeInterval(-9600),
        "Bridge repair is done. Trail's open again.",
        sender: "Frank Wilson", keySeed: 60, snr: 5.2, path: path
      ),
      MockMessageFactory.message(
        id: UUID(uuidString: "C2000000-0000-0000-0000-000000000002")!,
        createdAt: now.addingTimeInterval(-7200),
        text: "Nice work everyone.",
        direction: .outgoing,
        status: .sent,
        channelIndex: trailCrewChannelIndex,
        ackCode: 50002,
        pathLength: path
      )
    ]
  }

  /// Builds an incoming channel message, resolving the slot index from the id prefix.
  private static func channelIncoming(
    _ id: String,
    _ createdAt: Date,
    _ text: String,
    sender: String,
    keySeed: UInt8,
    snr: Double,
    path: UInt8,
    isRead: Bool = true,
    containsSelfMention: Bool = false,
    mentionSeen: Bool = false
  ) -> MessageDTO {
    let index: UInt8 = id.hasPrefix("C1") ? bayAreaChannelIndex
      : id.hasPrefix("C2") ? trailCrewChannelIndex
      : publicChannelIndex
    return MockMessageFactory.message(
      id: UUID(uuidString: id)!,
      createdAt: createdAt,
      text: text,
      direction: .incoming,
      channelIndex: index,
      pathLength: path,
      snr: snr,
      senderKeyPrefix: mockPublicKey(seed: keySeed).prefix(6),
      senderNodeName: sender,
      isRead: isRead,
      containsSelfMention: containsSelfMention,
      mentionSeen: mentionSeen
    )
  }
}
