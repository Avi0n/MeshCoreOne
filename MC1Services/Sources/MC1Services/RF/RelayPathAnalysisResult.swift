import Foundation

/// Combined result when analyzing a path via repeater
public struct RelayPathAnalysisResult: Equatable, Sendable {
    public let segmentAR: SegmentAnalysisResult
    public let segmentRB: SegmentAnalysisResult

    public init(segmentAR: SegmentAnalysisResult, segmentRB: SegmentAnalysisResult) {
        self.segmentAR = segmentAR
        self.segmentRB = segmentRB
    }

    public var totalDistanceMeters: Double {
        segmentAR.distanceMeters + segmentRB.distanceMeters
    }

    public var totalDistanceKm: Double { totalDistanceMeters / 1000 }

    /// Overall status is the worst of the two segments
    public var overallStatus: ClearanceStatus {
        let statusOrder: [ClearanceStatus] = [.clear, .marginal, .partialObstruction, .blocked]
        let arIndex = statusOrder.firstIndex(of: segmentAR.clearanceStatus) ?? 0
        let rbIndex = statusOrder.firstIndex(of: segmentRB.clearanceStatus) ?? 0
        return statusOrder[max(arIndex, rbIndex)]
    }
}
