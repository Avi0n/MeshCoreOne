import Foundation
import MC1Services

/// The resolved, validated send target carried across the actor boundary as a
/// Sendable DTO. The raw `@Model` never leaves `PersistenceStore`, so the intent
/// works with these DTOs.
enum MessageRecipient {
  case contact(ContactDTO)
  case channel(ChannelDTO)

  /// The radio the recipient was resolved against, used to confirm the live
  /// radio still owns it before enqueuing.
  var radioID: UUID {
    switch self {
    case let .contact(dto): dto.radioID
    case let .channel(dto): dto.radioID
    }
  }
}
