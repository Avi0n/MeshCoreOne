import Foundation

/// Mock data provider for iOS Simulator and demo mode testing
public enum MockDataProvider {
    // MARK: - Deterministic IDs

    /// Simulator device UUID
    public static let simulatorDeviceID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!

    /// Contact UUIDs
    public static let aliceChenID = UUID(uuidString: "00000000-0000-0000-0000-000000000010")!
    public static let bobMartinezID = UUID(uuidString: "00000000-0000-0000-0000-000000000020")!
    public static let charlieNodeID = UUID(uuidString: "00000000-0000-0000-0000-000000000030")!
    public static let dianasRoomID = UUID(uuidString: "00000000-0000-0000-0000-000000000040")!
    public static let eveThompsonID = UUID(uuidString: "00000000-0000-0000-0000-000000000050")!
    public static let frankWilsonID = UUID(uuidString: "00000000-0000-0000-0000-000000000060")!
    public static let ghostNodeID = UUID(uuidString: "00000000-0000-0000-0000-000000000070")!
    public static let hannahLeeID = UUID(uuidString: "00000000-0000-0000-0000-000000000080")!

    // MARK: - Mock Public Keys

    /// Generate a deterministic 32-byte public key from a seed
    private static func mockPublicKey(seed: UInt8) -> Data {
        Data((0..<ProtocolLimits.publicKeySize).map { UInt8($0) &+ seed })
    }

    // MARK: - Simulator Device

    /// Mock simulator device with realistic configuration
    public static var simulatorDevice: DeviceDTO {
        DeviceDTO(
            id: simulatorDeviceID,
            radioID: simulatorDeviceID,
            publicKey: mockPublicKey(seed: 1),
            nodeName: "Simulator Node",
            firmwareVersion: 8,
            firmwareVersionString: "v1.11.0",
            manufacturerName: "Mock Device",
            buildDate: "2025-12-20",
            maxContacts: 100,
            maxChannels: 8,
            frequency: 915_000,      // 915 MHz
            bandwidth: 250_000,      // 250 kHz
            spreadingFactor: 10,     // SF10
            codingRate: 5,           // 4/5
            txPower: 20,             // 20 dBm
            maxTxPower: 20,
            latitude: 37.7749,       // San Francisco
            longitude: -122.4194,
            blePin: 0,               // Disabled
            manualAddContacts: false,
            multiAcks: 2,
            telemetryModeBase: 2,
            telemetryModeLoc: 0,
            telemetryModeEnv: 0,
            advertLocationPolicy: 0,
            lastConnected: Date(),
            lastContactSync: 0,
            isActive: true,
            ocvPreset: nil,
            customOCVArrayString: nil
        )
    }

    // MARK: - Mock Contacts

    /// All mock contacts for simulator testing
    public static var contacts: [ContactDTO] {
        let now = Date()

        return [
            // Alice Chen - chat, normal, 3 unread, 2 hops
            ContactDTO(
                id: aliceChenID,
                radioID: simulatorDeviceID,
                publicKey: mockPublicKey(seed: 10),
                name: "Alice Chen",
                typeRawValue: ContactType.chat.rawValue,
                flags: 0,
                outPathLength: 2,
                outPath: Data([0x10, 0x20]),  // 2-hop path
                lastAdvertTimestamp: UInt32(now.timeIntervalSince1970) - 300,  // 5 min ago
                latitude: 37.7849,
                longitude: -122.4094,
                lastModified: UInt32(now.timeIntervalSince1970),
                nickname: nil,
                isBlocked: false,
                isMuted: false,
                isFavorite: false,
                lastMessageDate: now.addingTimeInterval(-1800),  // 30 min ago
                unreadCount: 3
            ),

            // Bob Martinez - chat, favorite, 1 hop (direct)
            ContactDTO(
                id: bobMartinezID,
                radioID: simulatorDeviceID,
                publicKey: mockPublicKey(seed: 20),
                name: "Bob Martinez",
                typeRawValue: ContactType.chat.rawValue,
                flags: 0,
                outPathLength: 1,
                outPath: Data([0x20]),  // Direct
                lastAdvertTimestamp: UInt32(now.timeIntervalSince1970) - 60,  // 1 min ago
                latitude: 37.7649,
                longitude: -122.4294,
                lastModified: UInt32(now.timeIntervalSince1970),
                nickname: nil,
                isBlocked: false,
                isMuted: false,
                isFavorite: true,
                lastMessageDate: now.addingTimeInterval(-900),  // 15 min ago
                unreadCount: 0
            ),

            // Charlie Node - repeater, 0 hops (self)
            ContactDTO(
                id: charlieNodeID,
                radioID: simulatorDeviceID,
                publicKey: mockPublicKey(seed: 30),
                name: "Charlie Node",
                typeRawValue: ContactType.repeater.rawValue,
                flags: 0,
                outPathLength: 0,
                outPath: Data(),
                lastAdvertTimestamp: UInt32(now.timeIntervalSince1970) - 120,  // 2 min ago
                latitude: 37.7549,
                longitude: -122.4394,
                lastModified: UInt32(now.timeIntervalSince1970),
                nickname: nil,
                isBlocked: false,
                isMuted: false,
                isFavorite: false,
                lastMessageDate: nil,
                unreadCount: 0
            ),

            // Diana's Room - room, 3 hops
            ContactDTO(
                id: dianasRoomID,
                radioID: simulatorDeviceID,
                publicKey: mockPublicKey(seed: 40),
                name: "Diana's Room",
                typeRawValue: ContactType.room.rawValue,
                flags: 0,
                outPathLength: 3,
                outPath: Data([0x10, 0x20, 0x40]),  // 3-hop path
                lastAdvertTimestamp: UInt32(now.timeIntervalSince1970) - 600,  // 10 min ago
                latitude: 37.7449,
                longitude: -122.4494,
                lastModified: UInt32(now.timeIntervalSince1970),
                nickname: nil,
                isBlocked: false,
                isMuted: false,
                isFavorite: false,
                lastMessageDate: nil,
                unreadCount: 0
            ),

            // Eve Thompson - chat, blocked, 4 hops
            ContactDTO(
                id: eveThompsonID,
                radioID: simulatorDeviceID,
                publicKey: mockPublicKey(seed: 50),
                name: "Eve Thompson",
                typeRawValue: ContactType.chat.rawValue,
                flags: 0,
                outPathLength: 4,
                outPath: Data([0x10, 0x20, 0x30, 0x50]),  // 4-hop path
                lastAdvertTimestamp: UInt32(now.timeIntervalSince1970) - 1800,  // 30 min ago
                latitude: 37.7349,
                longitude: -122.4594,
                lastModified: UInt32(now.timeIntervalSince1970),
                nickname: nil,
                isBlocked: true,
                isMuted: false,
                isFavorite: false,
                lastMessageDate: nil,
                unreadCount: 0
            ),

            // Frank Wilson - chat, nickname "Dad", 2 hops
            ContactDTO(
                id: frankWilsonID,
                radioID: simulatorDeviceID,
                publicKey: mockPublicKey(seed: 60),
                name: "Frank Wilson",
                typeRawValue: ContactType.chat.rawValue,
                flags: 0,
                outPathLength: 2,
                outPath: Data([0x10, 0x60]),  // 2-hop path
                lastAdvertTimestamp: UInt32(now.timeIntervalSince1970) - 3600,  // 1 hour ago
                latitude: 37.7249,
                longitude: -122.4694,
                lastModified: UInt32(now.timeIntervalSince1970),
                nickname: "Dad",
                isBlocked: false,
                isMuted: false,
                isFavorite: false,
                lastMessageDate: now.addingTimeInterval(-7200),  // 2 hours ago
                unreadCount: 0
            ),

            // Ghost Node - repeater, no recent contact, 5 hops
            ContactDTO(
                id: ghostNodeID,
                radioID: simulatorDeviceID,
                publicKey: mockPublicKey(seed: 70),
                name: "Ghost Node",
                typeRawValue: ContactType.repeater.rawValue,
                flags: 0,
                outPathLength: 5,
                outPath: Data([0x10, 0x20, 0x30, 0x40, 0x70]),  // 5-hop path (stale)
                lastAdvertTimestamp: UInt32(now.timeIntervalSince1970) - 86400,  // 24 hours ago
                latitude: 0,
                longitude: 0,
                lastModified: UInt32(now.timeIntervalSince1970) - 86400,
                nickname: nil,
                isBlocked: false,
                isMuted: false,
                isFavorite: false,
                lastMessageDate: nil,
                unreadCount: 0
            ),

            // Hannah Lee - chat, new/discovered, 1 hop
            ContactDTO(
                id: hannahLeeID,
                radioID: simulatorDeviceID,
                publicKey: mockPublicKey(seed: 80),
                name: "Hannah Lee",
                typeRawValue: ContactType.chat.rawValue,
                flags: 0,
                outPathLength: 1,
                outPath: Data([0x80]),  // Direct
                lastAdvertTimestamp: UInt32(now.timeIntervalSince1970) - 30,  // 30 seconds ago
                latitude: 37.7149,
                longitude: -122.4794,
                lastModified: UInt32(now.timeIntervalSince1970),
                nickname: nil,
                isBlocked: false,
                isMuted: false,
                isFavorite: false,
                lastMessageDate: nil,
                unreadCount: 0
            )
        ]
    }

    // MARK: - Mock Messages

    // swiftlint:disable function_body_length
    /// Generate mock messages for a specific contact
    public static func messages(for contactID: UUID) -> [MessageDTO] {
        let now = Date()
        let radioID = simulatorDeviceID

        switch contactID {
        case aliceChenID:
            // Alice: 5-6 messages with 3 unread incoming, delivered outgoing with ack, sent waiting for ack
            return [
                // Older delivered message (outgoing)
                MessageDTO(
                    id: UUID(uuidString: "10000000-0000-0000-0000-000000000001")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Hey Alice, are you free this weekend?",
                    timestamp: UInt32(now.addingTimeInterval(-86400).timeIntervalSince1970),  // 1 day ago
                    createdAt: now.addingTimeInterval(-86400),
                    direction: .outgoing,
                    status: .delivered,
                    textType: .plain,
                    ackCode: 12345,
                    pathLength: 2,
                    snr: nil,
                    senderKeyPrefix: nil,
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: 2500,  // 2.5 seconds
                    heardRepeats: 1,
                    retryAttempt: 0,
                    maxRetryAttempts: 3
                ),

                // Reply (incoming)
                MessageDTO(
                    id: UUID(uuidString: "10000000-0000-0000-0000-000000000002")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Yeah! Want to go hiking?",
                    timestamp: UInt32(now.addingTimeInterval(-82800).timeIntervalSince1970),  // 23 hours ago
                    createdAt: now.addingTimeInterval(-82800),
                    direction: .incoming,
                    status: .delivered,
                    textType: .plain,
                    ackCode: nil,
                    pathLength: 2,
                    snr: 8.5,
                    senderKeyPrefix: mockPublicKey(seed: 10).prefix(6),
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 0
                ),

                // Sent waiting for ack (outgoing)
                MessageDTO(
                    id: UUID(uuidString: "10000000-0000-0000-0000-000000000003")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Perfect! I know a great trail.",
                    timestamp: UInt32(now.addingTimeInterval(-7200).timeIntervalSince1970),  // 2 hours ago
                    createdAt: now.addingTimeInterval(-7200),
                    direction: .outgoing,
                    status: .sent,
                    textType: .plain,
                    ackCode: 12346,
                    pathLength: 2,
                    snr: nil,
                    senderKeyPrefix: nil,
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: nil,  // Still waiting for ACK
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 3
                ),

                // Unread message 1 (incoming)
                MessageDTO(
                    id: UUID(uuidString: "10000000-0000-0000-0000-000000000004")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Awesome! What time works for you?",
                    timestamp: UInt32(now.addingTimeInterval(-3600).timeIntervalSince1970),  // 1 hour ago
                    createdAt: now.addingTimeInterval(-3600),
                    direction: .incoming,
                    status: .delivered,
                    textType: .plain,
                    ackCode: nil,
                    pathLength: 2,
                    snr: 9.2,
                    senderKeyPrefix: mockPublicKey(seed: 10).prefix(6),
                    senderNodeName: nil,
                    isRead: false,  // Unread
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 0
                ),

                // Unread message 2 (incoming)
                MessageDTO(
                    id: UUID(uuidString: "10000000-0000-0000-0000-000000000005")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "How about 9am?",
                    timestamp: UInt32(now.addingTimeInterval(-2700).timeIntervalSince1970),  // 45 min ago
                    createdAt: now.addingTimeInterval(-2700),
                    direction: .incoming,
                    status: .delivered,
                    textType: .plain,
                    ackCode: nil,
                    pathLength: 2,
                    snr: 7.8,
                    senderKeyPrefix: mockPublicKey(seed: 10).prefix(6),
                    senderNodeName: nil,
                    isRead: false,  // Unread
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 0
                ),

                // Unread message 3 (incoming)
                MessageDTO(
                    id: UUID(uuidString: "10000000-0000-0000-0000-000000000006")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Let me know soon!",
                    timestamp: UInt32(now.addingTimeInterval(-1800).timeIntervalSince1970),  // 30 min ago
                    createdAt: now.addingTimeInterval(-1800),
                    direction: .incoming,
                    status: .delivered,
                    textType: .plain,
                    ackCode: nil,
                    pathLength: 2,
                    snr: 8.1,
                    senderKeyPrefix: mockPublicKey(seed: 10).prefix(6),
                    senderNodeName: nil,
                    isRead: false,  // Unread
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 0
                )
            ]

        case bobMartinezID:
            // Bob: 4-5 messages showing failed (retries exhausted), retrying, pending, delivered
            return [
                // Old delivered message (outgoing)
                MessageDTO(
                    id: UUID(uuidString: "20000000-0000-0000-0000-000000000001")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Bob, can you check the weather?",
                    timestamp: UInt32(now.addingTimeInterval(-172800).timeIntervalSince1970),  // 2 days ago
                    createdAt: now.addingTimeInterval(-172800),
                    direction: .outgoing,
                    status: .delivered,
                    textType: .plain,
                    ackCode: 23456,
                    pathLength: 1,
                    snr: nil,
                    senderKeyPrefix: nil,
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: 850,  // Fast, direct connection
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 3
                ),

                // Failed message (retries exhausted)
                MessageDTO(
                    id: UUID(uuidString: "20000000-0000-0000-0000-000000000002")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "This message failed to send",
                    timestamp: UInt32(now.addingTimeInterval(-7200).timeIntervalSince1970),  // 2 hours ago
                    createdAt: now.addingTimeInterval(-7200),
                    direction: .outgoing,
                    status: .failed,
                    textType: .plain,
                    ackCode: 23457,
                    pathLength: 1,
                    snr: nil,
                    senderKeyPrefix: nil,
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 3,  // Max retries reached
                    maxRetryAttempts: 3
                ),

                // Retrying message
                MessageDTO(
                    id: UUID(uuidString: "20000000-0000-0000-0000-000000000003")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Retrying this one...",
                    timestamp: UInt32(now.addingTimeInterval(-3600).timeIntervalSince1970),  // 1 hour ago
                    createdAt: now.addingTimeInterval(-3600),
                    direction: .outgoing,
                    status: .retrying,
                    textType: .plain,
                    ackCode: 23458,
                    pathLength: 1,
                    snr: nil,
                    senderKeyPrefix: nil,
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 1,  // First retry
                    maxRetryAttempts: 3
                ),

                // Pending message
                MessageDTO(
                    id: UUID(uuidString: "20000000-0000-0000-0000-000000000004")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Are you there?",
                    timestamp: UInt32(now.addingTimeInterval(-900).timeIntervalSince1970),  // 15 min ago
                    createdAt: now.addingTimeInterval(-900),
                    direction: .outgoing,
                    status: .pending,
                    textType: .plain,
                    ackCode: 23459,
                    pathLength: 1,
                    snr: nil,
                    senderKeyPrefix: nil,
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 3
                ),

                // Sending message
                MessageDTO(
                    id: UUID(uuidString: "20000000-0000-0000-0000-000000000005")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Testing connection...",
                    timestamp: UInt32(now.addingTimeInterval(-720).timeIntervalSince1970),  // 12 min ago
                    createdAt: now.addingTimeInterval(-720),
                    direction: .outgoing,
                    status: .sending,
                    textType: .plain,
                    ackCode: 23460,
                    pathLength: 1,
                    snr: nil,
                    senderKeyPrefix: nil,
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 3
                ),

                // Incoming reply
                MessageDTO(
                    id: UUID(uuidString: "20000000-0000-0000-0000-000000000006")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Yeah, I'm here!",
                    timestamp: UInt32(now.addingTimeInterval(-600).timeIntervalSince1970),  // 10 min ago
                    createdAt: now.addingTimeInterval(-600),
                    direction: .incoming,
                    status: .delivered,
                    textType: .plain,
                    ackCode: nil,
                    pathLength: 1,
                    snr: 12.3,  // Strong signal (direct)
                    senderKeyPrefix: mockPublicKey(seed: 20).prefix(6),
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 0
                )
            ]

        case frankWilsonID:
            // Frank: 2-3 messages with weak SNR values
            return [
                // Incoming with weak SNR
                MessageDTO(
                    id: UUID(uuidString: "60000000-0000-0000-0000-000000000001")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Hey kiddo, how are you?",
                    timestamp: UInt32(now.addingTimeInterval(-259200).timeIntervalSince1970),  // 3 days ago
                    createdAt: now.addingTimeInterval(-259200),
                    direction: .incoming,
                    status: .delivered,
                    textType: .plain,
                    ackCode: nil,
                    pathLength: 2,
                    snr: 2.1,  // Weak signal
                    senderKeyPrefix: mockPublicKey(seed: 60).prefix(6),
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 0
                ),

                // Outgoing reply
                MessageDTO(
                    id: UUID(uuidString: "60000000-0000-0000-0000-000000000002")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Doing great Dad! How about you?",
                    timestamp: UInt32(now.addingTimeInterval(-255600).timeIntervalSince1970),  // ~2.9 days ago
                    createdAt: now.addingTimeInterval(-255600),
                    direction: .outgoing,
                    status: .delivered,
                    textType: .plain,
                    ackCode: 34567,
                    pathLength: 2,
                    snr: nil,
                    senderKeyPrefix: nil,
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: 3200,  // Slower due to 2 hops
                    heardRepeats: 1,
                    retryAttempt: 0,
                    maxRetryAttempts: 3
                ),

                // Another incoming with very weak SNR
                MessageDTO(
                    id: UUID(uuidString: "60000000-0000-0000-0000-000000000003")!,
                    radioID: radioID,
                    contactID: contactID,
                    channelIndex: nil,
                    text: "Good! Talk soon.",
                    timestamp: UInt32(now.addingTimeInterval(-7200).timeIntervalSince1970),  // 2 hours ago
                    createdAt: now.addingTimeInterval(-7200),
                    direction: .incoming,
                    status: .delivered,
                    textType: .plain,
                    ackCode: nil,
                    pathLength: 2,
                    snr: 0.8,  // Very weak signal
                    senderKeyPrefix: mockPublicKey(seed: 60).prefix(6),
                    senderNodeName: nil,
                    isRead: true,
                    replyToID: nil,
                    roundTripTime: nil,
                    heardRepeats: 0,
                    retryAttempt: 0,
                    maxRetryAttempts: 0
                )
            ]

        default:
            // No messages for other contacts (Charlie, Diana, Eve, Ghost, Hannah)
            return []
        }
    }
    // swiftlint:enable function_body_length
}
