@testable import MC1Services
import Testing

@Suite("RegionalAreas")
struct RegionalAreasTests {
  @Test
  func `matchSubdivision finds California from normalized state name`() {
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "ca") == "US-CA")
  }

  @Test
  func `matchSubdivision finds Queensland from short suffix`() {
    #expect(RegionalAreas.matchSubdivision(country: "AU", normalized: "qld") == "AU-QLD")
  }

  @Test
  func `matchSubdivision returns nil for unknown subdivision`() {
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "zz") == nil)
  }

  @Test
  func `matchSubdivision returns nil for nil input`() {
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: nil) == nil)
  }

  @Test
  func `matchCounty finds Los Angeles in US-CA`() {
    #expect(RegionalAreas.matchCounty(country: "US", state: "US-CA", normalized: "los angeles") == "los angeles")
  }

  @Test
  func `matchCounty rejects unknown county`() {
    #expect(RegionalAreas.matchCounty(country: "US", state: "US-CA", normalized: "sacramento") == nil)
  }

  @Test
  func `matchCounty rejects non-US country`() {
    #expect(RegionalAreas.matchCounty(country: "CA", state: "CA-ON", normalized: "york") == nil)
  }

  @Test
  func `matchCounty rejects nil state`() {
    #expect(RegionalAreas.matchCounty(country: "US", state: nil, normalized: "los angeles") == nil)
  }

  @Test
  func `continents map covers known European countries`() {
    #expect(RegionalAreas.continents["DE"] == .europe)
    #expect(RegionalAreas.continents["GB"] == .europe)
    #expect(RegionalAreas.continents["PT"] == .europe)
  }

  @Test
  func `continents map covers Oceania and Asia`() {
    #expect(RegionalAreas.continents["AU"] == .oceania)
    #expect(RegionalAreas.continents["NZ"] == .oceania)
    #expect(RegionalAreas.continents["VN"] == .asia)
  }

  @Test
  func `Mexico is intentionally absent from continents`() {
    #expect(RegionalAreas.continents["MX"] == nil)
  }

  @Test
  func `displayName uses short form for US states`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA", source: .manual)
    #expect(RegionalAreas.displayName(for: region) == "California")
  }

  @Test
  func `displayName uses disambiguated form for AU territories`() {
    let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-QLD", source: .manual)
    let name = RegionalAreas.displayName(for: region)
    #expect(name.contains("Queensland"))
    #expect(name.contains("Australia"))
  }

  @Test
  func `displayName falls back to country name when admin is nil`() {
    let region = RegionSelection(countryCode: "US", source: .manual)
    #expect(RegionalAreas.displayName(for: region) == "United States")
  }

  @Test
  func `continents and countries cover the same set of country codes`() {
    // Adding a country to one table without the other silently breaks the picker
    // (visible but no recommendation) or the recommendation (no picker entry).
    let continentKeys = Set(RegionalAreas.continents.keys)
    let countryIDs = Set(RegionalAreas.countries.map(\.id))
    #expect(continentKeys == countryIDs)
  }
}
