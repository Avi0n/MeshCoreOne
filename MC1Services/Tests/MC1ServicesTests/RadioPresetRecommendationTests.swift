import Testing
@testable import MC1Services

@Suite("RadioPresets.recommended(for:)")
struct RadioPresetRecommendationTests {

    // MARK: - Tier 0 (county)

    @Test("LA, CA → WCMesh")
    func losAngelesGetsWCMesh() {
        let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                     countyKey: "los angeles", source: .location)
        #expect(RadioPresets.recommended(for: region)?.id == "wcmesh")
    }

    @Test("Sacramento (no countyKey match) → us-ca")
    func sacramentoFallsToCountry() {
        let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                     countyKey: "sacramento", source: .location)
        #expect(RadioPresets.recommended(for: region)?.id == "us-ca")
    }

    @Test("Manual California pick (countyKey nil) → us-ca")
    func manualCaliforniaIsCountryTier() {
        let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA", source: .manual)
        #expect(RadioPresets.recommended(for: region)?.id == "us-ca")
    }

    // MARK: - Tier 1 (sub-region)

    @Test("Queensland → au-qld")
    func queenslandGetsAUQLD() {
        let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-QLD", source: .location)
        #expect(RadioPresets.recommended(for: region)?.id == "au-qld")
    }

    @Test("Western Australia → au-sa-wa")
    func westernAustraliaGetsAUSAWA() {
        let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-WA", source: .location)
        #expect(RadioPresets.recommended(for: region)?.id == "au-sa-wa")
    }

    // MARK: - Tier 2 (country)

    @Test("Victoria, AU (no sub-region preset) → au-915 (Tier 2)")
    func victoriaAUFallsToAU915() {
        let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-VIC", source: .location)
        #expect(RadioPresets.recommended(for: region)?.id == "au-915")
    }

    @Test("Texas → us-ca")
    func texasGetsUSCA() {
        let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-TX", source: .location)
        #expect(RadioPresets.recommended(for: region)?.id == "us-ca")
    }

    @Test("Lisbon (PT) → pt-868 (priority 110 beats pt-433)")
    func lisbonGetsPT868() {
        let region = RegionSelection(countryCode: "PT", source: .location)
        #expect(RadioPresets.recommended(for: region)?.id == "pt-868")
    }

    @Test("Vietnam → vn-narrow (priority 110 beats deprecated vn)")
    func vietnamGetsVNNarrow() {
        let region = RegionSelection(countryCode: "VN", source: .location)
        #expect(RadioPresets.recommended(for: region)?.id == "vn-narrow")
    }

    // MARK: - Tier 3 (continent)

    @Test("Berlin (DE) → eu-narrow (priority 110 beats eu-lr)")
    func berlinGetsEUNarrow() {
        let region = RegionSelection(countryCode: "DE", source: .location)
        #expect(RadioPresets.recommended(for: region)?.id == "eu-narrow")
    }

    // MARK: - No match

    @Test("Bermuda → nil (no continent mapping)")
    func bermudaReturnsNil() {
        let region = RegionSelection(countryCode: "BM", source: .manual)
        #expect(RadioPresets.recommended(for: region) == nil)
    }

    // MARK: - presets(for:)

    @Test("presets(for: Sacramento) includes wcmesh in alternatives")
    func sacramentoAlternativesIncludeWCMesh() {
        let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                     countyKey: "sacramento", source: .location)
        let ids = RadioPresets.presets(for: region).map(\.id)
        #expect(ids.contains("wcmesh"))
        #expect(ids.contains("us-ca"))
    }

    @Test("presets(for: DE) returns continent-tier presets")
    func presetsForDEReturnsContinentPresets() {
        let region = RegionSelection(countryCode: "DE", source: .location)
        let ids = RadioPresets.presets(for: region).map(\.id)
        #expect(ids.contains("eu-narrow"))
        #expect(ids.contains("eu-lr"))
        #expect(!ids.contains("us-ca"))
    }

    @Test("presets(for: PT) returns country-and-below only")
    func presetsForPTSkipsContinent() {
        let region = RegionSelection(countryCode: "PT", source: .location)
        let ids = RadioPresets.presets(for: region).map(\.id)
        #expect(ids.contains("pt-868"))
        #expect(ids.contains("pt-433"))
        #expect(!ids.contains("eu-narrow"))
    }

    @Test("presets(for: VN) includes both vn-narrow and vn")
    func presetsForVNIncludesBoth() {
        let region = RegionSelection(countryCode: "VN", source: .location)
        let ids = RadioPresets.presets(for: region).map(\.id)
        #expect(ids.contains("vn-narrow"))
        #expect(ids.contains("vn"))
    }
}
