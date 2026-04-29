import Testing
@testable import MC1Services

@Suite("RegionalAreas")
struct RegionalAreasTests {

    @Test("matchSubdivision finds California from normalized state name")
    func matchSubdivisionCalifornia() {
        #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "ca") == "US-CA")
    }

    @Test("matchSubdivision finds Queensland from short suffix")
    func matchSubdivisionQueensland() {
        #expect(RegionalAreas.matchSubdivision(country: "AU", normalized: "qld") == "AU-QLD")
    }

    @Test("matchSubdivision returns nil for unknown subdivision")
    func matchSubdivisionUnknown() {
        #expect(RegionalAreas.matchSubdivision(country: "US", normalized: "zz") == nil)
    }

    @Test("matchSubdivision returns nil for nil input")
    func matchSubdivisionNilInput() {
        #expect(RegionalAreas.matchSubdivision(country: "US", normalized: nil) == nil)
    }

    @Test("matchCounty finds Los Angeles in US-CA")
    func matchCountyLosAngeles() {
        #expect(RegionalAreas.matchCounty(country: "US", state: "US-CA", normalized: "los angeles") == "los angeles")
    }

    @Test("matchCounty rejects unknown county")
    func matchCountyUnknown() {
        #expect(RegionalAreas.matchCounty(country: "US", state: "US-CA", normalized: "sacramento") == nil)
    }

    @Test("matchCounty rejects non-US country")
    func matchCountyNonUS() {
        #expect(RegionalAreas.matchCounty(country: "CA", state: "CA-ON", normalized: "york") == nil)
    }

    @Test("matchCounty rejects nil state")
    func matchCountyNilState() {
        #expect(RegionalAreas.matchCounty(country: "US", state: nil, normalized: "los angeles") == nil)
    }

    @Test("continents map covers known European countries")
    func continentsEurope() {
        #expect(RegionalAreas.continents["DE"] == .europe)
        #expect(RegionalAreas.continents["GB"] == .europe)
        #expect(RegionalAreas.continents["PT"] == .europe)
    }

    @Test("continents map covers Oceania and Asia")
    func continentsOceaniaAsia() {
        #expect(RegionalAreas.continents["AU"] == .oceania)
        #expect(RegionalAreas.continents["NZ"] == .oceania)
        #expect(RegionalAreas.continents["VN"] == .asia)
    }

    @Test("Mexico is intentionally absent from continents")
    func continentsMexicoAbsent() {
        #expect(RegionalAreas.continents["MX"] == nil)
    }

    @Test("displayName uses short form for US states")
    func displayNameUSShort() {
        let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA", source: .manual)
        #expect(RegionalAreas.displayName(for: region) == "California")
    }

    @Test("displayName uses disambiguated form for AU territories")
    func displayNameAUDisambiguated() {
        let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-QLD", source: .manual)
        let name = RegionalAreas.displayName(for: region)
        #expect(name.contains("Queensland"))
        #expect(name.contains("Australia"))
    }

    @Test("displayName falls back to country name when admin is nil")
    func displayNameCountryOnly() {
        let region = RegionSelection(countryCode: "US", source: .manual)
        #expect(RegionalAreas.displayName(for: region) == "United States")
    }
}
