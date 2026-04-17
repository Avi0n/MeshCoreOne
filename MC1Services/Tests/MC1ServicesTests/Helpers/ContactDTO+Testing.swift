import Foundation
import MeshCore
@testable import MC1Services

extension ContactDTO {

    /// Creates a ContactDTO with sensible test defaults.
    ///
    /// Usage:
    /// ```
    /// let contact = ContactDTO.testContact(deviceID: myDeviceID)
    /// let repeater = ContactDTO.testContact(deviceID: myDeviceID, typeRawValue: ContactType.repeater.rawValue)
    /// ```
    static func testContact(
        id: UUID = UUID(),
        deviceID: UUID = UUID(),
        publicKey: Data = Data(repeating: 0xAB, count: 32),
        name: String = "TestContact",
        typeRawValue: UInt8 = ContactType.chat.rawValue,
        flags: UInt8 = 0,
        outPathLength: UInt8 = 0xFF,
        outPath: Data = Data(),
        lastAdvertTimestamp: UInt32 = 0,
        latitude: Double = 0,
        longitude: Double = 0,
        lastModified: UInt32 = 0,
        lastHeardTimestamp: UInt32 = 0,
        nickname: String? = nil,
        isBlocked: Bool = false,
        isMuted: Bool = false,
        isFavorite: Bool = false,
        lastMessageDate: Date? = nil,
        unreadCount: Int = 0,
        unreadMentionCount: Int = 0
    ) -> ContactDTO {
        ContactDTO(
            id: id,
            deviceID: deviceID,
            publicKey: publicKey,
            name: name,
            typeRawValue: typeRawValue,
            flags: flags,
            outPathLength: outPathLength,
            outPath: outPath,
            lastAdvertTimestamp: lastAdvertTimestamp,
            latitude: latitude,
            longitude: longitude,
            lastModified: lastModified,
            lastHeardTimestamp: lastHeardTimestamp,
            nickname: nickname,
            isBlocked: isBlocked,
            isMuted: isMuted,
            isFavorite: isFavorite,
            lastMessageDate: lastMessageDate,
            unreadCount: unreadCount,
            unreadMentionCount: unreadMentionCount
        )
    }
}
