import Foundation
@testable import MC1Services

extension RoomMessageDTO {

    /// Creates a RoomMessageDTO with sensible test defaults.
    ///
    /// Usage:
    /// ```
    /// let message = RoomMessageDTO.testRoomMessage(sessionID: mySessionID)
    /// ```
    static func testRoomMessage(
        id: UUID = UUID(),
        sessionID: UUID,
        authorKeyPrefix: Data = Data([0xAB, 0xCD, 0xEF, 0x01]),
        authorName: String? = "TestAuthor",
        text: String = "Hello from the room",
        timestamp: UInt32 = 1_700_000_000,
        createdAt: Date = Date(),
        isFromSelf: Bool = false,
        status: MessageStatus = .delivered
    ) -> RoomMessageDTO {
        RoomMessageDTO(
            id: id,
            sessionID: sessionID,
            authorKeyPrefix: authorKeyPrefix,
            authorName: authorName,
            text: text,
            timestamp: timestamp,
            createdAt: createdAt,
            isFromSelf: isFromSelf,
            status: status
        )
    }
}
