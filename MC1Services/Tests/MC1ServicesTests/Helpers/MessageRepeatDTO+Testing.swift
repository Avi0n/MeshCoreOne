import Foundation
@testable import MC1Services

extension MessageRepeatDTO {
  /// Creates a MessageRepeatDTO with sensible test defaults.
  ///
  /// Usage:
  /// ```
  /// let repeat = MessageRepeatDTO.testRepeat(messageID: myMessageID)
  /// ```
  static func testRepeat(
    id: UUID = UUID(),
    messageID: UUID,
    receivedAt: Date = Date(),
    pathNodes: Data = Data([0x31]),
    pathLength: UInt8 = 0,
    snr: Double? = 8.5,
    rssi: Int? = -90,
    rxLogEntryID: UUID? = nil
  ) -> MessageRepeatDTO {
    MessageRepeatDTO(
      id: id,
      messageID: messageID,
      receivedAt: receivedAt,
      pathNodes: pathNodes,
      pathLength: pathLength,
      snr: snr,
      rssi: rssi,
      rxLogEntryID: rxLogEntryID
    )
  }
}
