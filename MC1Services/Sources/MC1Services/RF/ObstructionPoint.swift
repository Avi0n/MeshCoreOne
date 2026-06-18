import Foundation

/// Point where obstruction affects the path
public struct ObstructionPoint: Identifiable, Equatable, Sendable {
    public let id = UUID()
    public let distanceFromAMeters: Double
    public let obstructionHeightMeters: Double
    public let fresnelClearancePercent: Double

    public init(
        distanceFromAMeters: Double,
        obstructionHeightMeters: Double,
        fresnelClearancePercent: Double
    ) {
        self.distanceFromAMeters = distanceFromAMeters
        self.obstructionHeightMeters = obstructionHeightMeters
        self.fresnelClearancePercent = fresnelClearancePercent
    }

    public static func == (lhs: ObstructionPoint, rhs: ObstructionPoint) -> Bool {
        lhs.distanceFromAMeters == rhs.distanceFromAMeters
            && lhs.obstructionHeightMeters == rhs.obstructionHeightMeters
            && lhs.fresnelClearancePercent == rhs.fresnelClearancePercent
    }
}
