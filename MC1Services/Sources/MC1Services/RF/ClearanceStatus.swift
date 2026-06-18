import Foundation

/// Clearance status at worst point along path
public enum ClearanceStatus: String, Sendable {
    case clear = "Clear"
    case marginal = "Marginal"
    case partialObstruction = "Partial obstruction"
    case blocked = "Blocked"
}
