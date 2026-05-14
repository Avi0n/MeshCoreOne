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
