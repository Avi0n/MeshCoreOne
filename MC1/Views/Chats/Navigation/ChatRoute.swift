import Foundation
import MC1Services

enum ChatRoute: Hashable {
    case direct(ContactDTO)
    case channel(ChannelDTO)
    case room(RemoteNodeSessionDTO)

    enum Kind: UInt8, Hashable {
        case direct
        case channel
        case room
    }

    var kind: Kind {
        switch self {
        case .direct:
            return .direct
        case .channel:
            return .channel
        case .room:
            return .room
        }
    }

    var conversationID: UUID {
        switch self {
        case .direct(let contact):
            return contact.id
        case .channel(let channel):
            return channel.id
        case .room(let session):
            return session.id
        }
    }

    static func == (lhs: ChatRoute, rhs: ChatRoute) -> Bool {
        lhs.kind == rhs.kind && lhs.conversationID == rhs.conversationID
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(kind)
        hasher.combine(conversationID)
    }

    init(conversation: Conversation) {
        switch conversation {
        case .direct(let contact):
            self = .direct(contact)
        case .channel(let channel):
            self = .channel(channel)
        case .room(let session):
            self = .room(session)
        }
    }

    var roomIsConnected: Bool? {
        guard case .room(let session) = self else { return nil }
        return session.isConnected
    }

    func toConversation() -> Conversation {
        switch self {
        case .direct(let contact):
            return .direct(contact)
        case .channel(let channel):
            return .channel(channel)
        case .room(let session):
            return .room(session)
        }
    }

    func refreshedPayload(from conversations: [Conversation]) -> ChatRoute? {
        guard let match = conversations.first(where: { conversation in
            let route = ChatRoute(conversation: conversation)
            return route.kind == kind && route.conversationID == conversationID
        }) else {
            return nil
        }

        return ChatRoute(conversation: match)
    }
}
