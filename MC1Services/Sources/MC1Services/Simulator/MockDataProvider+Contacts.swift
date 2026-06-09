import Foundation

extension MockDataProvider {
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

            // Bob Martinez - chat, 1 hop (direct)
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
                isFavorite: false,
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

            // Hannah Lee - chat, direct, short greeting conversation
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
                isFavorite: true,
                lastMessageDate: now.addingTimeInterval(-600),  // 10 min ago
                unreadCount: 0
            )
        ]
    }
}
