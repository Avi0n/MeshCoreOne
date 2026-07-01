import Foundation

/// Result for a single path segment (A to R, or R to B)
public struct SegmentAnalysisResult: Equatable, Sendable {
  public let startLabel: String
  public let endLabel: String
  public let clearanceStatus: ClearanceStatus
  public let distanceMeters: Double
  public let worstClearancePercent: Double

  public init(
    startLabel: String,
    endLabel: String,
    clearanceStatus: ClearanceStatus,
    distanceMeters: Double,
    worstClearancePercent: Double
  ) {
    self.startLabel = startLabel
    self.endLabel = endLabel
    self.clearanceStatus = clearanceStatus
    self.distanceMeters = distanceMeters
    self.worstClearancePercent = worstClearancePercent
  }

  public var distanceKm: Double {
    distanceMeters / 1000
  }
}
