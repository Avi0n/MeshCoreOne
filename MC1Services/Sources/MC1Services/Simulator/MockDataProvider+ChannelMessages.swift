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
    default: return []
    }
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
