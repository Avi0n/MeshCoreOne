import Foundation

/// Shared interface for types that can be matched by `RepeaterResolver`.
/// Both `ContactDTO` and `DiscoveredNodeDTO` conform.
public protocol RepeaterResolvable: Sendable {
    var publicKey: Data { get }
    var latitude: Double { get }
    var longitude: Double { get }
    var hasLocation: Bool { get }
    var lastAdvertTimestamp: UInt32 { get }
    /// Secondary recency tiebreaker (ContactDTO → lastModified, DiscoveredNodeDTO → lastHeard).
    var recencyDate: Date { get }
    /// Display name used for path hops and resolver tiebreaking.
    var resolvableName: String { get }
}
