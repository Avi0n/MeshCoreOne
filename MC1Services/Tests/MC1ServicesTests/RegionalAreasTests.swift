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
  func `Costa Rica and Slovakia are in both continent and country tables`() {
    #expect(RegionalAreas.continents["CR"] == .northAmerica)
    #expect(RegionalAreas.continents["SK"] == .europe)
    #expect(RegionalAreas.countries.map(\.id).contains("CR"))
    #expect(RegionalAreas.countries.map(\.id).contains("SK"))
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

  @Test
  func `usSubdivisions lists all 50 states and DC`() {
    let ids = Set(RegionalAreas.usSubdivisions.map(\.id))
    #expect(ids.count == 51)
    #expect(ids.contains("US-CA"))
    #expect(ids.contains("US-TX"))
    #expect(ids.contains("US-DC"))
    #expect(ids.contains("US-HI"))
  }

  @Test
  func `auSubdivisions lists all states and territories`() {
    let ids = Set(RegionalAreas.auSubdivisions.map(\.id))
    #expect(ids == Set([
      "AU-ACT", "AU-NSW", "AU-NT", "AU-QLD", "AU-SA", "AU-TAS", "AU-VIC", "AU-WA",
    ]))
  }

  @Test
  func `administrativeAreaKind is state for US and AU, province for Canada`() {
    #expect(RegionalAreas.administrativeAreaKind(for: "US") == .state)
    #expect(RegionalAreas.administrativeAreaKind(for: "AU") == .state)
    #expect(RegionalAreas.administrativeAreaKind(for: "CA") == .province)
    #expect(RegionalAreas.administrativeAreaKind(for: "PT") == .state)
  }

  @Test
  func `matchSubdivision finds Pennsylvania from long and postal names`() {
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "pa") == "US-PA")
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "pennsylvania") == "US-PA")
  }

  @Test
  func `matchSubdivision finds New Jersey from long and postal names`() {
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "nj") == "US-NJ")
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "new jersey") == "US-NJ")
  }

  @Test
  func `matchSubdivision finds Delaware from long and postal names`() {
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "de") == "US-DE")
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "delaware") == "US-DE")
  }

  @Test
  func `matchSubdivision finds Maryland from long and postal names`() {
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "md") == "US-MD")
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "maryland") == "US-MD")
  }

  @Test
  func `matchSubdivision finds Texas from long and postal names`() {
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "tx") == "US-TX")
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "texas") == "US-TX")
  }

  @Test
  func `matchSubdivision finds Victoria from long and postal names`() {
    #expect(RegionalAreas.matchSubdivision(country: "AU", normalized: "vic") == "AU-VIC")
    #expect(RegionalAreas.matchSubdivision(country: "AU", normalized: "victoria") == "AU-VIC")
  }

  @Test
  func `matchSubdivision returns nil for uncatalogued US territory`() {
    #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "pr") == nil)
  }

  @Test
  func `displayName uses short form for Texas`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-TX", source: .manual)
    #expect(RegionalAreas.displayName(for: region) == "Texas")
  }

  @Test
  func `matchCounty for US-PA stays nil`() {
    #expect(RegionalAreas.matchCounty(country: "US", state: "US-PA", normalized: "philadelphia") == nil)
  }

  @Test
  func `displayName uses short form for Pennsylvania`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-PA", source: .manual)
    #expect(RegionalAreas.displayName(for: region) == "Pennsylvania")
  }

  @Test
  func `showsSubdivisionPicker is true for US and AU, false for PT and nil`() {
    #expect(RegionalAreas.showsSubdivisionPicker(for: "US"))
    #expect(RegionalAreas.showsSubdivisionPicker(for: "AU"))
    #expect(!RegionalAreas.showsSubdivisionPicker(for: "PT"))
    #expect(!RegionalAreas.showsSubdivisionPicker(for: "CA"))
    #expect(!RegionalAreas.showsSubdivisionPicker(for: nil))
  }
}
