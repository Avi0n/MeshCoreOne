import Foundation

// MARK: - Packet Size Constants

/// Named constants for packet size validation to avoid magic numbers.
enum PacketSize {
  /// Full contact structure size.
  static let contact = 147
  /// Minimum size for self info response.
  static let selfInfoMinimum = 57
  /// Minimum size for message sent confirmation.
  static let messageSentMinimum = 9
  /// Minimum size for version 1 contact messages.
  static let contactMessageV1Minimum = 12
  /// Minimum size for version 3 contact messages.
  static let contactMessageV3Minimum = 15
  /// Minimum size for version 1 channel messages.
  static let channelMessageV1Minimum = 7
  /// Minimum size for version 3 channel messages.
  static let channelMessageV3Minimum = 10
  /// Minimum size for private key export.
  static let privateKeyMinimum = 64
  /// Minimum size for basic battery info.
  static let batteryMinimum = 2
  /// Size for battery info with storage statistics.
  static let batteryExtended = 10
  /// Minimum size for signing session start.
  static let signStartMinimum = 5
  /// Full size for version 3 device info.
  static let deviceInfoV3Full = 79
  /// Minimum size for acknowledgement packets.
  static let ackMinimum = 4
  /// Size for acknowledgement packets that include firmware trip time.
  static let ackWithTripTime = 8
  /// Minimum size for contact synchronization start.
  static let contactsStartMinimum = 4
  /// Minimum size for core system statistics.
  static let coreStatsMinimum = 9
  /// Minimum size for radio statistics.
  static let radioStatsMinimum = 12
  /// Minimum size for packet counters.
  static let packetStatsMinimum = 24
  /// Size for packet counters with receive errors field.
  static let packetStatsWithReceiveErrors = 28
  /// Minimum size for channel configuration info.
  static let channelInfoMinimum = 49
  /// Size of the public key in contact deleted notifications.
  static let contactDeletedPublicKey = 32
  /// Minimum size for status response push notification.
  /// Format: `reserved(1) + pubkey(6) + fields(51) = 58 bytes`
  static let statusResponseMinimum = 58
  /// Minimum size for trace route data.
  static let traceDataMinimum = 11
  /// Minimum size for raw packet data.
  /// Format: `[snr:1][rssi:1][reserved:1]`
  static let rawDataMinimum = 3
  /// Minimum size for control protocol data.
  static let controlDataMinimum = 4
  /// Minimum size for path discovery results.
  /// Format: `reserved(1) + pubkey(6) + out_path_len(1) + in_path_len(1) = 9 bytes`
  static let pathDiscoveryMinimum = 9
  /// Minimum size for login success response (legacy format).
  /// Format: `[adminIndicator:1][pubkeyPrefix:6]` (companion radio hardcodes `0` for
  /// legacy "OK" replies).
  static let loginSuccessMinimum = 7
  /// Size for v7+ login success with ACL permissions.
  /// Format: `[adminIndicator:1][pubkeyPrefix:6][timestamp:4][aclPermissions:1][fwVersion:1]`
  /// where `adminIndicator == 1` means admin; any other value is non-admin.
  static let loginSuccessExtended = 13
  /// Size for binary response status payload without rxAirtime field (48 bytes).
  static let binaryResponseStatusBase = 48
  /// Minimum size for binary response status payload with rxAirtime field (52 bytes).
  static let binaryResponseStatusWithRxAirtime = 52
  /// Minimum size for binary response status payload with receiveErrors field (56 bytes).
  static let binaryResponseStatusWithReceiveErrors = 56
  /// Minimum size for channel datagram payload (header fields before data).
  /// Format: `[snr:1][rsv:2][channel:1][path_len:1][data_type:2][data_len:1]` = 8 bytes.
  static let channelDatagramMinimum = 8
  /// Default-scope name-field width on the wire (zero-padded, null-terminated).
  static let defaultFloodScopeNameField = 31
  /// Default-scope key width on the wire.
  static let defaultFloodScopeKeyBytes = 16
  /// Size for populated default flood scope response (name field + key).
  static let defaultFloodScopeSet = defaultFloodScopeNameField + defaultFloodScopeKeyBytes
}
