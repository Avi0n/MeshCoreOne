import Foundation
import MC1Services

extension ChatViewModel {

    // MARK: - Timestamp Helpers

    /// Time gap (in seconds) that breaks message grouping for timestamps and sender names.
    static let messageGroupingGapSeconds = 300

    /// Pre-computed display flags for a single message.
    struct DisplayFlags {
        let showTimestamp: Bool
        let showDirectionGap: Bool
        let showSenderName: Bool
    }

    /// Computes all display flags in a single pass to avoid redundant message lookups.
    /// Used by buildDisplayItems() for O(n) performance instead of O(3n).
    static func computeDisplayFlags(for message: MessageDTO, previous: MessageDTO?) -> DisplayFlags {
        guard let previous else {
            return DisplayFlags(showTimestamp: true, showDirectionGap: false, showSenderName: true)
        }

        // Uses createdAt to stay consistent with the message sort order — switching to a
        // different timestamp field would silently break grouping at sort boundaries.
        let timeGap = abs(Int(message.createdAt.timeIntervalSince(previous.createdAt)))

        let showTimestamp = timeGap > messageGroupingGapSeconds
        let showDirectionGap = message.direction != previous.direction

        let showSenderName: Bool
        if message.contactID != nil || message.isOutgoing {
            // UI suppresses the sender name for direct messages anyway; the branch
            // keeps the channel-message logic from running with a missing senderNodeName.
            showSenderName = true
        } else if previous.isOutgoing || timeGap > messageGroupingGapSeconds {
            showSenderName = true
        } else if let currentName = message.senderNodeName, let previousName = previous.senderNodeName {
            showSenderName = currentName != previousName
        } else {
            // No senderNodeName available on either side; show the name to be safe.
            showSenderName = true
        }

        return DisplayFlags(showTimestamp: showTimestamp, showDirectionGap: showDirectionGap, showSenderName: showSenderName)
    }
}
