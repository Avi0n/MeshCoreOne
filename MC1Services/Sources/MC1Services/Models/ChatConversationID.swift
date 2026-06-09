import Foundation

/// Uniquely identifies a (radio, conversation) pair so a single
/// `ChatCoordinator` instance can be shared across multiple
/// `ChatViewModel`s — iPad split view, sheet dismissal, navigation
/// transitions. The `ChatCoordinatorRegistry` keys its dictionary on
/// this value.
public struct ChatConversationID: Hashable, Sendable {
    public let radioID: UUID
    public let conversation: ConversationKey

    public enum ConversationKey: Hashable, Sendable {
        case dm(contactID: UUID)
        case channel(channelIndex: UInt8)
    }

    public init(radioID: UUID, conversation: ConversationKey) {
        self.radioID = radioID
        self.conversation = conversation
    }
}

public extension ChatConversationID {
    static func dm(radioID: UUID, contactID: UUID) -> ChatConversationID {
        ChatConversationID(radioID: radioID, conversation: .dm(contactID: contactID))
    }

    static func channel(radioID: UUID, channelIndex: UInt8) -> ChatConversationID {
        ChatConversationID(radioID: radioID, conversation: .channel(channelIndex: channelIndex))
    }
}

public extension ChatConversationID {
    /// Separator between the segments of a draft storage key.
    private static let keySeparator = "|"
    /// Segment marking a direct-message draft key.
    private static let dmKeySegment = "dm"
    /// Segment marking a channel draft key.
    private static let channelKeySegment = "ch"

    /// Stable, pinned string encoding used as the `DraftStore` dictionary key.
    /// Always namespaced by `radioID` so drafts never leak across radios; DMs key
    /// on the contact UUID, channels on the slot index. The format is frozen by a
    /// unit test — changing it silently orphans every previously persisted draft.
    var draftStorageKey: String {
        switch conversation {
        case .dm(let contactID):
            return [radioID.uuidString, Self.dmKeySegment, contactID.uuidString]
                .joined(separator: Self.keySeparator)
        case .channel(let channelIndex):
            return [radioID.uuidString, Self.channelKeySegment, String(channelIndex)]
                .joined(separator: Self.keySeparator)
        }
    }
}
