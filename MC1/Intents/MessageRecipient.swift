import Foundation
import MC1Services

/// The resolved, validated send target carried across the actor boundary as a
/// Sendable DTO. The raw `@Model` never leaves `PersistenceStore`, so the intent
/// works with these DTOs.
enum MessageRecipient: Sendable {
    case contact(ContactDTO)
    case channel(ChannelDTO)

    /// The radio the recipient was resolved against, used to confirm the live
    /// radio still owns it before enqueuing.
    var radioID: UUID {
        switch self {
        case .contact(let dto): dto.radioID
        case .channel(let dto): dto.radioID
        }
    }
}
