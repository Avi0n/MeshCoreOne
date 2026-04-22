import Foundation
import MeshCore

/// Resolves the ``FloodScope`` to push to the radio for a given conversation,
/// combining the per-channel ``ChannelFloodScope`` preference with the device-wide
/// default flood scope name.
///
/// - `.inherit` + device default set → `.region(default)` (radio floods on the default)
/// - `.inherit` + no device default → `.disabled` (all-regions / no scope filter)
/// - `.allRegions` → `.disabled` (explicit override of the default)
/// - `.region(name)` → `.region(name)` (explicit per-channel override)
public enum ChannelFloodScopeResolver {
    public static func resolve(
        channelFloodScope: ChannelFloodScope,
        deviceDefaultFloodScopeName: String?
    ) -> FloodScope {
        switch channelFloodScope {
        case .inherit:
            if let name = deviceDefaultFloodScopeName, !name.isEmpty {
                return .region(name)
            }
            return .disabled
        case .allRegions:
            return .disabled
        case .region(let name):
            return .region(name)
        }
    }
}
