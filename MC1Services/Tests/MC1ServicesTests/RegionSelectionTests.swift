@testable import MC1Services
import Testing

@Suite("RegionSelection picker writes")
struct RegionSelectionTests {
  @Test
  func `afterChoosingCountry skips a re-tap and otherwise writes a manual country`() {
    let current = RegionSelection(
      countryCode: "US",
      administrativeAreaCode: "US-CA",
      countyKey: "los angeles",
      source: .location
    )
    #expect(RegionSelection.afterChoosingCountry("US", current: current) == nil)
    #expect(
      RegionSelection.afterChoosingCountry("PT", current: current)
        == RegionSelection(countryCode: "PT", source: .manual)
    )
    #expect(
      RegionSelection.afterChoosingCountry("US", current: nil)
        == RegionSelection(countryCode: "US", source: .manual)
    )
  }

  @Test
  func `afterChoosingSubdivision skips a re-tap, drops county on change, and needs a country`() {
    let current = RegionSelection(
      countryCode: "US",
      administrativeAreaCode: "US-CA",
      countyKey: "los angeles",
      source: .location
    )
    #expect(RegionSelection.afterChoosingSubdivision("US-CA", current: current) == nil)
    #expect(
      RegionSelection.afterChoosingSubdivision("US-TX", current: current)
        == RegionSelection(
          countryCode: "US",
          administrativeAreaCode: "US-TX",
          source: .manual
        )
    )
    #expect(RegionSelection.afterChoosingSubdivision("US-CA", current: nil) == nil)
  }
}
