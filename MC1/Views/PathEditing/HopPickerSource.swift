import Foundation
import MC1Services

/// Data source for the shared Add-Hop picker (`AddHopPickerView`). Lets the same
/// picker drive both the contact path editor (`PathManagementViewModel`, hop-capped)
/// and the trace path builder (`TracePathViewModel`, uncapped) without depending on
/// either concretely.
@MainActor
protocol HopPickerSource: AnyObject {
    var availableRepeaters: [ContactDTO] { get }
    var availableRooms: [ContactDTO] { get }
    var discoveredRepeaters: [DiscoveredNodeDTO] { get }
    var recentPublicKeys: [Data] { get }

    /// Hops currently in the path being built.
    var currentHopCount: Int { get }
    /// Maximum hops the path can hold, or `nil` when unlimited (trace).
    var hopLimit: Int? { get }
    /// Whether no further hops can be added. Defaults to `currentHopCount >= hopLimit`.
    var isPathFull: Bool { get }

    /// Append a single node to the path and record it as recent.
    func appendHop(_ node: some RepeaterResolvable)
    /// Add every resolvable code from a comma-separated bulk entry, honoring the hop cap.
    func addCodes(_ input: String) -> CodeInputResult
    /// Classify a comma-separated bulk entry per code without mutating the path,
    /// for the bulk-add preview panel.
    func classifyCodes(_ input: String) -> [HopCodeClassification]
}

extension HopPickerSource {
    /// A path with no `hopLimit` is never full; otherwise it fills at the cap.
    var isPathFull: Bool {
        guard let hopLimit else { return false }
        return currentHopCount >= hopLimit
    }
}
