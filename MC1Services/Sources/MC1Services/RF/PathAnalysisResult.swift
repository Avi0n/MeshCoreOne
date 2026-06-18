import Foundation

/// Complete analysis result for a path
public struct PathAnalysisResult: Equatable, Sendable {
    public let distanceMeters: Double
    public let freeSpacePathLoss: Double
    /// Peak diffraction loss from the single worst knife-edge obstruction (not cumulative)
    public let peakDiffractionLoss: Double
    public let totalPathLoss: Double
    public let clearanceStatus: ClearanceStatus
    public let worstClearancePercent: Double
    public let obstructionPoints: [ObstructionPoint]
    public let frequencyMHz: Double
    public let refractionK: Double

    public init(
        distanceMeters: Double,
        freeSpacePathLoss: Double,
        peakDiffractionLoss: Double,
        totalPathLoss: Double,
        clearanceStatus: ClearanceStatus,
        worstClearancePercent: Double,
        obstructionPoints: [ObstructionPoint],
        frequencyMHz: Double,
        refractionK: Double
    ) {
        self.distanceMeters = distanceMeters
        self.freeSpacePathLoss = freeSpacePathLoss
        self.peakDiffractionLoss = peakDiffractionLoss
        self.totalPathLoss = totalPathLoss
        self.clearanceStatus = clearanceStatus
        self.worstClearancePercent = worstClearancePercent
        self.obstructionPoints = obstructionPoints
        self.frequencyMHz = frequencyMHz
        self.refractionK = refractionK
    }

    public var distanceKm: Double { distanceMeters / 1000 }

    public var worstObstructionPoint: ObstructionPoint? {
        obstructionPoints.min(by: { $0.fresnelClearancePercent < $1.fresnelClearancePercent })
    }

    /// Returns the worst obstruction point per contiguous obstructed region.
    /// Groups adjacent obstruction points by sample spacing, then picks the
    /// lowest clearance point from each group, one per red bar in the terrain profile.
    public var peakObstructionPerRegion: [ObstructionPoint] {
        guard obstructionPoints.count >= 2 else { return obstructionPoints }

        // Find the smallest gap between consecutive points (= one sample step)
        var minGap = Double.infinity
        for i in 1..<obstructionPoints.count {
            let gap = obstructionPoints[i].distanceFromAMeters - obstructionPoints[i - 1].distanceFromAMeters
            if gap > 0 && gap < minGap { minGap = gap }
        }
        guard minGap.isFinite else { return [obstructionPoints[0]] }

        // A gap > 2x the sample step means a non-obstructed sample separates two regions
        let gapThreshold = minGap * 2.5

        var regions: [ObstructionPoint] = []
        var regionWorst = obstructionPoints[0]

        for i in 1..<obstructionPoints.count {
            let point = obstructionPoints[i]
            let gap = point.distanceFromAMeters - obstructionPoints[i - 1].distanceFromAMeters

            if gap > gapThreshold {
                regions.append(regionWorst)
                regionWorst = point
            } else if point.fresnelClearancePercent < regionWorst.fresnelClearancePercent {
                regionWorst = point
            }
        }
        regions.append(regionWorst)

        return regions
    }
}
