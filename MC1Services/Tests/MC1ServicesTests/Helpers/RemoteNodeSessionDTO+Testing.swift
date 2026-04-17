import Foundation
@testable import MC1Services

extension RemoteNodeSessionDTO {

    /// Creates a RemoteNodeSessionDTO with sensible test defaults.
    ///
    /// Usage:
    /// ```
    /// let session = RemoteNodeSessionDTO.testSession(radioID: myRadioID)
    /// let room = RemoteNodeSessionDTO.testSession(radioID: myRadioID, role: .roomServer)
    /// ```
    static func testSession(
        id: UUID = UUID(),
        radioID: UUID,
        publicKey: Data = Data(repeating: 0xCC, count: 32),
        name: String = "TestNode",
        role: RemoteNodeRole = .roomServer,
        latitude: Double = 0,
        longitude: Double = 0,
        isConnected: Bool = false,
        permissionLevel: RoomPermissionLevel = .guest,
        lastConnectedDate: Date? = nil,
        unreadCount: Int = 0,
        notificationLevel: NotificationLevel = .all,
        isFavorite: Bool = false,
        neighborCount: Int = 0,
        lastSyncTimestamp: UInt32 = 0,
        lastMessageDate: Date? = nil
    ) -> RemoteNodeSessionDTO {
        RemoteNodeSessionDTO(
            id: id,
            radioID: radioID,
            publicKey: publicKey,
            name: name,
            role: role,
            latitude: latitude,
            longitude: longitude,
            isConnected: isConnected,
            permissionLevel: permissionLevel,
            lastConnectedDate: lastConnectedDate,
            unreadCount: unreadCount,
            notificationLevel: notificationLevel,
            isFavorite: isFavorite,
            neighborCount: neighborCount,
            lastSyncTimestamp: lastSyncTimestamp,
            lastMessageDate: lastMessageDate
        )
    }
}
