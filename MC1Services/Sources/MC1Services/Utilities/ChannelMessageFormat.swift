import Foundation

/// Utilities for parsing the "NodeName: MessageText" format used in channel messages.
/// The firmware prepends the sender's node name before encryption.
public enum ChannelMessageFormat {
    /// Parses "NodeName: MessageText" format from decrypted channel messages.
    /// - Parameter text: The full decrypted channel message text
    /// - Returns: Tuple of (senderName, messageText) or nil if format doesn't match
    public static func parse(_ text: String) -> (senderName: String, messageText: String)? {
        guard let colonIndex = text.firstIndex(of: ":"),
              colonIndex != text.startIndex else {
            return nil
        }

        let senderName = String(text[..<colonIndex])
        let afterColon = text.index(after: colonIndex)

        guard afterColon < text.endIndex else {
            return (senderName, "")
        }

        let messageText = String(text[afterColon...]).trimmingCharacters(in: .whitespaces)
        return (senderName, messageText)
    }
}
