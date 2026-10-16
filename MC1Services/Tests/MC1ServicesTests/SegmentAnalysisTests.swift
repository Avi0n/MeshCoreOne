import CoreLocation
@testable import MC1Services
import Testing

@Suite("Segment Analysis Types")
struct SegmentAnalysisTypeTests {
  @Test
  func `SegmentAnalysisResult stores segment data`() {
    let result = SegmentAnalysisResult(
      startLabel: "A",
      endLabel: "R",
      clearanceStatus: .clear,
      distanceMeters: 5000,
      worstClearancePercent: 85
    )

    #expect(result.startLabel == "A")
    #expect(result.endLabel == "R")
    #expect(result.clearanceStatus == .clear)
    #expect(result.distanceMeters == 5000)
    #expect(result.worstClearancePercent == 85)
  }

  @Test
  func `RelayPathAnalysisResult combines segments`() {
    let segmentAR = SegmentAnalysisResult(
      startLabel: "A",
      endLabel: "R",
      clearanceStatus: .clear,
      distanceMeters: 5000,
      worstClearancePercent: 85
    )
    let segmentRB = SegmentAnalysisResult(
      startLabel: "R",
      endLabel: "B",
      clearanceStatus: .marginal,
      distanceMeters: 3000,
      worstClearancePercent: 65
    )

    let result = RelayPathAnalysisResult(
      segmentAR: segmentAR,
      segmentRB: segmentRB
    )

    #expect(result.segmentAR.distanceMeters == 5000)
    #expect(result.segmentRB.distanceMeters == 3000)
    #expect(result.totalDistanceMeters == 8000)
    #expect(result.overallStatus == .marginal) // worst of the two
  }

  @Test
  func `RelayPathAnalysisResult overall status is worst of segments`() {
    // Both clear -> clear
    let bothClear = RelayPathAnalysisResult(
      segmentAR: SegmentAnalysisResult(
        startLabel: "A", endLabel: "R", clearanceStatus: .clear, distanceMeters: 1000,
        worstClearancePercent: 90
      ),
      segmentRB: SegmentAnalysisResult(
        startLabel: "R", endLabel: "B", clearanceStatus: .clear, distanceMeters: 1000,
        worstClearancePercent: 85
      )
    )
    #expect(bothClear.overallStatus == .clear)

    // One blocked -> blocked
    let oneBlocked = RelayPathAnalysisResult(
      segmentAR: SegmentAnalysisResult(
        startLabel: "A", endLabel: "R", clearanceStatus: .clear, distanceMeters: 1000,
        worstClearancePercent: 90
      ),
      segmentRB: SegmentAnalysisResult(
        startLabel: "R", endLabel: "B", clearanceStatus: .blocked, distanceMeters: 1000,
        worstClearancePercent: -10
      )
    )
    #expect(oneBlocked.overallStatus == .blocked)
  }
}
