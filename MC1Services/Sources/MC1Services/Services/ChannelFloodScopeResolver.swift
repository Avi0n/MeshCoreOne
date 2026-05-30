import Foundation
import MeshCore

/// Resolves the ``ResolvedFloodScope`` to push to the radio for a given conversation,
/// combining the per-channel ``ChannelFloodScope`` preference with the device-wide
/// default flood scope name and the device's un-scoped-send capability.
///
/// - `.allRegions` + device supports un-scoped send → `.unscoped` (true override of the
///   device default via firmware sub-command 1; requires firmware v12+)
/// - `.allRegions` + device does not support un-scoped send → `.scope(.disabled)`
///   (best-effort fallback: a zero-key reset cannot override the default on older firmware,
///   so the radio still floods on its configured default)
/// - `.inherit` + device default set → `.scope(.region(default))` (radio floods on the default)
/// - `.inherit` + no device default → `.scope(.disabled)` (no scope filter)
/// - `.region(name)` → `.scope(.region(name))` (explicit per-channel override)
public enum ChannelFloodScopeResolver {
    public static func resolve(
        channelFloodScope: ChannelFloodScope,
        deviceDefaultFloodScopeName: String?,
        supportsUnscopedFloodSend: Bool
    ) -> ResolvedFloodScope {
        switch channelFloodScope {
        case .inherit:
            if let name = deviceDefaultFloodScopeName, !name.isEmpty {
                return .scope(.region(name))
            }
            return .scope(.disabled)
        case .allRegions:
            return supportsUnscopedFloodSend ? .unscoped : .scope(.disabled)
        case .region(let name):
            return .scope(.region(name))
        }
    }
}
