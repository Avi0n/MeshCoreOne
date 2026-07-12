import Foundation

extension MockDataProvider {
  /// A seeded offline link preview. `saveMessage` drops the link-preview columns,
  /// so these are applied via `updateMessageLinkPreview` after the message is saved.
  struct LinkPreviewSeed {
    let messageID: UUID
    let url: String
    let title: String
    let imageData: Data
  }

  /// Link previews to apply after their parent messages are saved.
  static var linkPreviewSeeds: [LinkPreviewSeed] {
    [
      LinkPreviewSeed(
        messageID: aliceLinkPreviewMessageID,
        url: "https://meshcoreone.com/trails/skyline",
        title: "Skyline Ridge Trail Guide",
        imageData: demoImageData
      )
    ]
  }

  /// Generate mock messages for a specific contact.
  public static func messages(for contactID: UUID) -> [MessageDTO] {
    let now = Date()
    switch contactID {
    case aliceChenID: return aliceMessages(now: now)
    case bobMartinezID: return bobMessages(now: now)
    case frankWilsonID: return frankMessages(now: now)
    case hannahLeeID: return hannahMessages(now: now)
    default: return [] // Charlie, Diana, Eve, Ghost remain contact-list-only fixtures
    }
  }

  // MARK: - Per-contact builders

  /// Alice (2-hop): reply, reaction badge, offline link preview, inline image,
  /// clock-corrected incoming, signed-plain outgoing, varied path-hash byte size.
  private static func aliceMessages(now: Date) -> [MessageDTO] {
    let key = mockPublicKey(seed: 10).prefix(6)
    return [
      MockMessageFactory.message(
        id: UUID(uuidString: "10000000-0000-0000-0000-000000000001")!,
        createdAt: now.addingTimeInterval(-90000),
        text: "Hey Alice, are you free this weekend?",
        direction: .outgoing,
        status: .delivered,
        contactID: aliceChenID,
        ackCode: 12345,
        pathLength: encodePathLen(hashSize: 1, hopCount: 2),
        roundTripTime: 2500,
        heardRepeats: 1
      ),
      // Reacted message (badge applied via updateMessageReactionSummary).
      MockMessageFactory.message(
        id: aliceReactedMessageID,
        createdAt: now.addingTimeInterval(-86400),
        text: "Yeah! Want to go hiking? 🥾",
        direction: .incoming,
        contactID: aliceChenID,
        pathLength: encodePathLen(hashSize: 2, hopCount: 2),
        snr: 8.5,
        pathNodes: Data([0x10, 0xA3, 0x20, 0xB7]), // 2 hops x 2-byte hash
        senderKeyPrefix: key
      ),
      // Outgoing reply referencing Alice's reacted message.
      MockMessageFactory.message(
        id: UUID(uuidString: "10000000-0000-0000-0000-000000000003")!,
        createdAt: now.addingTimeInterval(-82000),
        text: "Perfect! I know a great trail.",
        direction: .outgoing,
        status: .sent,
        contactID: aliceChenID,
        ackCode: 12346,
        pathLength: encodePathLen(hashSize: 1, hopCount: 2),
        replyToID: aliceReactedMessageID
      ),
      // Signed-plain outgoing (CLI/signed styling).
      MockMessageFactory.message(
        id: UUID(uuidString: "10000000-0000-0000-0000-000000000009")!,
        createdAt: now.addingTimeInterval(-78000),
        text: "see you at 9am",
        direction: .outgoing,
        status: .delivered,
        contactID: aliceChenID,
        textType: .signedPlain,
        ackCode: 12347,
        pathLength: encodePathLen(hashSize: 1, hopCount: 2)
      ),
      // Clock-corrected incoming: wire send time skewed 2h behind the corrected time.
      MockMessageFactory.message(
        id: UUID(uuidString: "10000000-0000-0000-0000-00000000000C")!,
        createdAt: now.addingTimeInterval(-7200),
        text: "Sorry, my clock was way off 😅",
        direction: .incoming,
        contactID: aliceChenID,
        pathLength: encodePathLen(hashSize: 1, hopCount: 2),
        snr: 6.5,
        pathNodes: Data([0x10, 0x20]),
        senderKeyPrefix: key,
        isRead: false,
        timestampCorrected: true,
        senderTimestamp: UInt32(now.addingTimeInterval(-14400).timeIntervalSince1970)
      ),
      // Offline link preview (URL in body; preview applied post-save).
      MockMessageFactory.message(
        id: aliceLinkPreviewMessageID,
        createdAt: now.addingTimeInterval(-5400),
        text: "Trail details here: https://meshcoreone.com/trails/skyline",
        direction: .incoming,
        contactID: aliceChenID,
        pathLength: encodePathLen(hashSize: 1, hopCount: 2),
        snr: 7.9,
        pathNodes: Data([0x10, 0x20]),
        senderKeyPrefix: key,
        isRead: false
      ),
      // Inline image (URL in body; bytes pre-seeded into the app-layer cache).
      MockMessageFactory.message(
        id: UUID(uuidString: "10000000-0000-0000-0000-00000000000B")!,
        createdAt: now.addingTimeInterval(-3600),
        text: "Made it to the summit! \(inlineImageURL)",
        direction: .incoming,
        contactID: aliceChenID,
        pathLength: encodePathLen(hashSize: 2, hopCount: 2),
        snr: 8.2,
        pathNodes: Data([0x10, 0xA3, 0x20, 0xB7]),
        senderKeyPrefix: key,
        isRead: false
      )
    ]
  }

  /// Bob (direct): full delivery-status spread (pending through failed/retrying).
  private static func bobMessages(now: Date) -> [MessageDTO] {
    let key = mockPublicKey(seed: 20).prefix(6)
    let direct = encodePathLen(hashSize: 1, hopCount: 1)
    return [
      MockMessageFactory.message(
        id: UUID(uuidString: "20000000-0000-0000-0000-000000000001")!,
        createdAt: now.addingTimeInterval(-172_800),
        text: "Bob, can you check the weather?",
        direction: .outgoing,
        status: .delivered,
        contactID: bobMartinezID,
        ackCode: 23456,
        pathLength: direct,
        roundTripTime: 850
      ),
      MockMessageFactory.message(
        id: UUID(uuidString: "20000000-0000-0000-0000-000000000002")!,
        createdAt: now.addingTimeInterval(-7200),
        text: "This message failed to send",
        direction: .outgoing,
        status: .failed,
        contactID: bobMartinezID,
        ackCode: 23457,
        pathLength: direct,
        retryAttempt: 3
      ),
      MockMessageFactory.message(
        id: UUID(uuidString: "20000000-0000-0000-0000-000000000003")!,
        createdAt: now.addingTimeInterval(-3600),
        text: "Retrying this one...",
        direction: .outgoing,
        status: .retrying,
        contactID: bobMartinezID,
        ackCode: 23458,
        pathLength: direct,
        retryAttempt: 1
      ),
      MockMessageFactory.message(
        id: UUID(uuidString: "20000000-0000-0000-0000-000000000004")!,
        createdAt: now.addingTimeInterval(-900),
        text: "Are you there?",
        direction: .outgoing,
        status: .pending,
        contactID: bobMartinezID,
        ackCode: 23459,
        pathLength: direct
      ),
      MockMessageFactory.message(
        id: UUID(uuidString: "20000000-0000-0000-0000-000000000005")!,
        createdAt: now.addingTimeInterval(-720),
        text: "Testing connection...",
        direction: .outgoing,
        status: .sending,
        contactID: bobMartinezID,
        ackCode: 23460,
        pathLength: direct
      ),
      MockMessageFactory.message(
        id: UUID(uuidString: "20000000-0000-0000-0000-000000000006")!,
        createdAt: now.addingTimeInterval(-600),
        text: "Yeah, I'm here!",
        direction: .incoming,
        contactID: bobMartinezID,
        pathLength: direct,
        snr: 12.3, // strong, direct
        pathNodes: Data([0x20]),
        senderKeyPrefix: key
      )
    ]
  }

  /// Frank "Dad" (2-hop): weak/very-weak SNR, a flood-routed incoming with a region
  /// scope, and a heard-repeat-backed outgoing (repeats seeded separately).
  private static func frankMessages(now: Date) -> [MessageDTO] {
    let key = mockPublicKey(seed: 60).prefix(6)
    return [
      MockMessageFactory.message(
        id: UUID(uuidString: "60000000-0000-0000-0000-000000000001")!,
        createdAt: now.addingTimeInterval(-259_200),
        text: "Hey kiddo, how are you?",
        direction: .incoming,
        contactID: frankWilsonID,
        pathLength: encodePathLen(hashSize: 2, hopCount: 2),
        snr: 2.1, // weak
        pathNodes: Data([0x10, 0x4F, 0x60, 0x9C]),
        senderKeyPrefix: key
      ),
      // Heard-repeat-backed outgoing; heardRepeats matches the seeded repeat rows.
      MockMessageFactory.message(
        id: frankRepeatMessageID,
        createdAt: now.addingTimeInterval(-255_600),
        text: "Doing great Dad! How about you?",
        direction: .outgoing,
        status: .delivered,
        contactID: frankWilsonID,
        ackCode: 34567,
        pathLength: encodePathLen(hashSize: 1, hopCount: 2),
        roundTripTime: 3200,
        heardRepeats: 3
      ),
      MockMessageFactory.message(
        id: UUID(uuidString: "60000000-0000-0000-0000-000000000003")!,
        createdAt: now.addingTimeInterval(-10800),
        text: "Good! Talk soon.",
        direction: .incoming,
        contactID: frankWilsonID,
        pathLength: encodePathLen(hashSize: 2, hopCount: 2),
        snr: 0.8, // very weak
        pathNodes: Data([0x10, 0x4F, 0x60, 0x9C]),
        senderKeyPrefix: key
      ),
      // Flood-routed incoming carrying a region scope.
      MockMessageFactory.message(
        id: UUID(uuidString: "60000000-0000-0000-0000-000000000004")!,
        createdAt: now.addingTimeInterval(-3600),
        text: "Storm warning for the ridge tonight ⛈️",
        direction: .incoming,
        contactID: frankWilsonID,
        pathLength: encodePathLen(hashSize: 1, hopCount: 3),
        snr: 3.2,
        pathNodes: Data([0x10, 0x44, 0x60]),
        senderKeyPrefix: key,
        routeType: .tcFlood,
        regionScope: "US915"
      )
    ]
  }

  /// Hannah (direct): short greeting plus a message with a coordinate (map preview).
  private static func hannahMessages(now: Date) -> [MessageDTO] {
    let key = mockPublicKey(seed: 80).prefix(6)
    let direct = encodePathLen(hashSize: 1, hopCount: 1)
    return [
      MockMessageFactory.message(
        id: UUID(uuidString: "80000000-0000-0000-0000-000000000001")!,
        createdAt: now.addingTimeInterval(-1200),
        text: "Hi! This is Hannah from the trail club 👋",
        direction: .incoming,
        contactID: hannahLeeID,
        pathLength: direct,
        snr: 9.0,
        pathNodes: Data([0x80]),
        senderKeyPrefix: key
      ),
      MockMessageFactory.message(
        id: UUID(uuidString: "80000000-0000-0000-0000-000000000002")!,
        createdAt: now.addingTimeInterval(-900),
        text: "Hey Hannah! Welcome aboard.",
        direction: .outgoing,
        status: .delivered,
        contactID: hannahLeeID,
        ackCode: 45678,
        pathLength: direct
      ),
      // Coordinate in body renders a map-preview fragment.
      MockMessageFactory.message(
        id: UUID(uuidString: "80000000-0000-0000-0000-000000000003")!,
        createdAt: now.addingTimeInterval(-600),
        text: "Meet me at the trailhead: 37.8651, -119.5383",
        direction: .incoming,
        contactID: hannahLeeID,
        pathLength: direct,
        snr: 8.7,
        pathNodes: Data([0x80]),
        senderKeyPrefix: key
      )
    ]
  }
}
