import Foundation
import SwiftUI
import MC1Services

extension ChatViewModel {

    // MARK: - Display Flags

    /// Time gap (in seconds) that breaks message grouping for timestamps and sender names.
    static let messageGroupingGapSeconds = 300

    /// Pre-computed display flags for a single message.
    struct DisplayFlags {
        let showTimestamp: Bool
        let showDirectionGap: Bool
        let showSenderName: Bool
    }

    /// Computes all display flags in a single pass to avoid redundant message lookups.
    /// Used by buildItems() for O(n) performance instead of O(3n).
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

    // MARK: - Item Build

    /// Assemble `MessageBuildInputs` from current view-model state. Reads
    /// `previewStates`, `cachedURLs`, `decodedImages`, etc. plus `envInputs`
    /// (`@MainActor` state). Pure with respect to the inputs — given the same
    /// state it returns the same value, so it is safe to call from the main
    /// actor and feed the resulting `Sendable` snapshot to an off-main builder.
    func makeBuildInputs(for message: MessageDTO, previous: MessageDTO?) -> MessageBuildInputs {
        let flags = Self.computeDisplayFlags(for: message, previous: previous)
        return MessageBuildInputs(
            messageID: message.id,
            previewState: previewStates[message.id] ?? .idle,
            loadedPreview: loadedPreviews[message.id],
            cachedURL: cachedURLs[message.id].flatMap { $0 },
            hasInlineImageRef: decodedImages[message.id] != nil,
            hasPreviewImageRef: decodedPreviewAssets[message.id]?.image != nil,
            hasPreviewIconRef: decodedPreviewAssets[message.id]?.icon != nil,
            imageIsGIF: imageIsGIF[message.id] ?? false,
            formattedText: MessageText.buildFormattedText(
                text: message.text,
                isOutgoing: message.isOutgoing,
                currentUserName: envInputs.currentUserName,
                isHighContrast: envInputs.isHighContrast
            ),
            baseColor: message.isOutgoing ? .outgoing : .incoming,
            formattedPath: (envInputs.showIncomingPath && !message.isOutgoing)
                ? MessagePathFormatter.format(message)
                : nil,
            senderResolution: senderResolutionFor(message),
            showTimestamp: flags.showTimestamp,
            showDirectionGap: flags.showDirectionGap,
            showSenderName: flags.showSenderName,
            showNewMessagesDivider: message.id == newMessagesDividerMessageID
        )
    }

    /// Single-message convenience that pairs `makeBuildInputs` with the pure
    /// `MessageFragmentBuilder`. Single-row callers (`appendMessageIfNew`,
    /// `rebuildDisplayItem`, `updateURLForDisplayItem`) keep using this; the
    /// batch path in `buildItems()` calls `makeBuildInputs` on main and then
    /// invokes the builder off-actor with the resulting snapshot.
    func makeItem(for message: MessageDTO, previous: MessageDTO?) -> MessageItem {
        MessageFragmentBuilder.makeItem(
            for: message,
            inputs: makeBuildInputs(for: message, previous: previous),
            envInputs: envInputs
        )
    }

    /// Recover the previous message in display order from the canonical
    /// `messages` array. Survives reordering side effects (e.g.,
    /// `reorderSameSenderClusters`) because it reads the current array at
    /// call time, not an item-index snapshot.
    func previousMessage(for messageID: UUID) -> MessageDTO? {
        guard let index = messages.firstIndex(where: { $0.id == messageID }),
              index > 0 else { return nil }
        return messages[index - 1]
    }

    /// Resolve a sender display name for a message in the current conversation.
    /// Channels run the contact-aware resolver; DMs fall back to the unknown
    /// sentinel because DM bubbles never display the sender row.
    func senderResolutionFor(_ message: MessageDTO) -> NodeNameResolution {
        if currentChannel != nil {
            return MessageBubbleConfiguration.resolveSenderName(
                for: message,
                contacts: allContacts
            )
        }
        return NodeNameResolution(
            displayName: L10n.Chats.Chats.Message.Sender.unknown,
            matchKind: .unresolved
        )
    }
}
