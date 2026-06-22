import Foundation

/// Protocol size limits and constants
public enum ProtocolLimits {
    public static let publicKeySize = 32
    public static let advertTimestampSize = 4
    public static let privateKeySize = 64
    public static let maxPathSize = 64
    public static let maxFrameSize = 172
    public static let signatureSize = 64
    public static let maxPacketPayload = 184
    public static let cipherMacSize = 2
    public static let maxHashSize = 8
    public static let cipherKeySize = 16
    public static let maxContacts = 100
    public static let offlineQueueSize = 16
    public static let maxNameLength = 32
    public static let channelSecretSize = 16
    public static let maxMessageLength = 160

    /// Maximum usable bytes for names (firmware char[32] minus null terminator)
    public static let maxUsableNameBytes = 31

    /// Maximum UTF-8 bytes for the default flood scope name field. The firmware field is 31
    /// bytes with zero padding and accepts `0 < strlen(name) < 31`, so the effective cap is
    /// 30. Longer inputs must be truncated before send; otherwise the stored display and the
    /// full-name-derived scope key would disagree.
    public static let maxDefaultFloodScopeNameBytes = 30

    /// Maximum UTF-8 bytes for direct message text. App-enforced at 150, which
    /// sits under the firmware's binding `MAX_TEXT_LEN` of 160 so the message
    /// always fits the wire buffer.
    public static let maxDirectMessageLength = 150

    /// Total limit for channel messages including "NodeName: " prefix
    public static let maxChannelMessageTotalLength = 147

    /// Max user text bytes for channel messages, accounting for node name prefix
    public static func maxChannelMessageLength(nodeNameByteCount: Int) -> Int {
        max(0, maxChannelMessageTotalLength - nodeNameByteCount - 2)
    }
}
