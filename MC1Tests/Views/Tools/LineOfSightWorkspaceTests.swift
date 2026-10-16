import CoreGraphics
@testable import MC1
import Testing

@Suite("Line of Sight workspace")
struct LineOfSightWorkspaceTests {
  @Test
  func `compact phone size uses map with sheet`() {
    #expect(
      LineOfSightLayoutMode.preferred(in: CGSize(width: 390, height: 844)) == .mapWithSheet
    )
  }

  @Test
  func `regular iPad size pairs analysis and map`() {
    #expect(
      LineOfSightLayoutMode.preferred(in: CGSize(width: 1024, height: 768)) == .paired
    )
  }

  @Test
  func `wide but short landscape does not pair`() {
    #expect(
      LineOfSightLayoutMode.preferred(in: CGSize(width: 844, height: 390)) == .mapWithSheet
    )
  }

  @Test
  func `regular iPad analysis column uses the preferred width`() {
    #expect(
      LineOfSightLayoutMode.analysisColumnWidth(in: CGSize(width: 1024, height: 768))
        == LineOfSightLayoutMode.preferredAnalysisWidth
    )
  }

  @Test
  func `analysis column never steals the map minimum`() {
    let tight = CGSize(
      width: LineOfSightLayoutMode.minimumAnalysisWidth
        + LineOfSightLayoutMode.minimumMapWidth,
      height: 768
    )
    #expect(LineOfSightLayoutMode.preferred(in: tight) == .paired)
    #expect(
      LineOfSightLayoutMode.analysisColumnWidth(in: tight)
        == LineOfSightLayoutMode.minimumAnalysisWidth
    )
  }

  @Test
  func `compact overlay padding is the map/sheet overlap plus sheet gap`() {
    #expect(LineOfSightMapOverlayPadding.amount(overlayMaxY: 800, sheetMinY: 600) == 196)
  }

  @Test
  func `compact overlay padding falls back before frames exist`() {
    #expect(LineOfSightMapOverlayPadding.amount(overlayMaxY: 0, sheetMinY: 0) == 220)
    #expect(LineOfSightMapOverlayPadding.amount(overlayMaxY: 800, sheetMinY: 900) == 220)
  }
}
