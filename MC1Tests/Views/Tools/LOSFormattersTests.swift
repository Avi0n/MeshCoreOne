import Foundation
@testable import MC1
import Testing

@Suite("Line of Sight clearance formatting")
struct LOSFormattersTests {
  @Test(arguments: [
    (-50.0, "-50%"),
    (167.0, "167%"),
    (-0.0022239, "-0.00222%")
  ])
  func `Clearance keeps its sign and range`(percent: Double, expected: String) throws {
    let style = LOSFormatters.clearancePercentFormat.locale(Locale(identifier: "en_US"))

    #expect(percent.formatted(style) == expected)
    let rounded = try #require(Double(String(expected.dropLast())))
    let parsed = try LOSFormatters.clearancePercentFormat.parseStrategy.parse(LOSFormatters.formatClearancePercent(percent))
    #expect(abs(parsed - rounded) < 1e-12)
  }

  @Test
  func `Clearance uses the locale decimal separator and percent spacing`() {
    let style = LOSFormatters.clearancePercentFormat.locale(Locale(identifier: "de_DE"))

    #expect((-0.5).formatted(style) == "-0,5\u{00A0}%")
  }
}
