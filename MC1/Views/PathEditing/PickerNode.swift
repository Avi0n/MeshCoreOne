import Foundation
import MC1Services

/// Unified node type for the repeater picker list
enum PickerNode: Identifiable {
    case contact(ContactDTO)
    case discovered(DiscoveredNodeDTO)

    var id: UUID {
        switch self {
        case .contact(let c): c.id
        case .discovered(let d): d.id
        }
    }

    var displayName: String {
        switch self {
        case .contact(let c): c.displayName
        case .discovered(let d): d.name
        }
    }

    var publicKeyHex: String {
        switch self {
        case .contact(let c): c.publicKey.uppercaseHexString()
        case .discovered(let d): d.publicKey.uppercaseHexString()
        }
    }

    var isRoom: Bool {
        switch self {
        case .contact(let c): c.type == .room
        case .discovered: false
        }
    }

    var isDiscovered: Bool {
        switch self {
        case .contact: false
        case .discovered: true
        }
    }

    /// True iff this node is a contact flagged as a user favorite.
    /// Discovered nodes are never favorites — `DiscoveredNodeDTO` has no `isFavorite` field.
    var isFavorite: Bool {
        switch self {
        case .contact(let c): c.isFavorite
        case .discovered: false
        }
    }

    /// The underlying DTO for passing to ViewModel methods
    var underlying: any RepeaterResolvable {
        switch self {
        case .contact(let c): c
        case .discovered(let d): d
        }
    }
}
