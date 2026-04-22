import Foundation

/// Represents a channel's flood-scope preference. Orthogonal to the device-level
/// default flood scope — `inherit` means "apply the device default at send time";
/// `allRegions` means "override the device default and broadcast to all regions";
/// `region(name)` is a per-channel override to a specific named region.
public enum ChannelFloodScope: Sendable, Equatable, Hashable {
    case inherit
    case allRegions
    case region(String)
}

/// Bridges the two-field on-disk representation (`floodScopeModeRawValue: String` +
/// `regionScope: String?`) and the public ``ChannelFloodScope`` enum. Invalid storage
/// combinations are not representable externally: if `.specific` mode is set but
/// `regionName` is nil, recompose yields `.inherit` defensively.
enum ChannelFloodScopeStorage {
    enum Mode: String {
        // Raw values are pinned so a case rename can't silently change what's
        // persisted to SwiftData, written into backups, or matched by the migration predicate.
        // swiftlint:disable redundant_string_enum_value
        case inherit = "inherit"
        case allRegions = "allRegions"
        case specific = "specific"
        // swiftlint:enable redundant_string_enum_value
    }

    static func decompose(_ scope: ChannelFloodScope) -> (mode: Mode, regionName: String?) {
        switch scope {
        case .inherit: return (.inherit, nil)
        case .allRegions: return (.allRegions, nil)
        case .region(let name): return (.specific, name)
        }
    }

    static func recompose(modeRawValue: String, regionName: String?) -> ChannelFloodScope {
        switch Mode(rawValue: modeRawValue) ?? .inherit {
        case .inherit: return .inherit
        case .allRegions: return .allRegions
        case .specific:
            guard let name = regionName, !name.isEmpty else { return .inherit }
            return .region(name)
        }
    }
}
