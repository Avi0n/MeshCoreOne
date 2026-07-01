@testable import MC1Services
import Testing

@Suite("RadioPresets.recommended(for:)")
struct RadioPresetRecommendationTests {
  // MARK: - Tier 0 (county)

  @Test
  func `LA, CA → WCMesh`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                 countyKey: "los angeles", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "wcmesh")
  }

  @Test
  func `Sacramento (no countyKey match) → us-ca`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                 countyKey: "sacramento", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "us-ca")
  }

  @Test
  func `Manual California pick (countyKey nil) → us-ca`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA", source: .manual)
    #expect(RadioPresets.recommended(for: region)?.id == "us-ca")
  }

  // MARK: - Tier 1 (sub-region)

  @Test
  func `Queensland → au-qld`() {
    let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-QLD", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "au-qld")
  }

  @Test
  func `Western Australia → au-sa-wa`() {
    let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-WA", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "au-sa-wa")
  }

  // MARK: - Tier 2 (country)

  @Test
  func `Victoria, AU (no sub-region preset) → au-915 (Tier 2)`() {
    let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-VIC", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "au-915")
  }

  @Test
  func `Texas → us-ca`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-TX", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "us-ca")
  }

  @Test
  func `Lisbon (PT) → pt-868 (priority 110 beats pt-433)`() {
    let region = RegionSelection(countryCode: "PT", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "pt-868")
  }

  @Test
  func `Vietnam → vn-narrow (priority 110 beats deprecated vn)`() {
    let region = RegionSelection(countryCode: "VN", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "vn-narrow")
  }

  @Test
  func `Netherlands → nl (country tier beats EU continent)`() {
    let region = RegionSelection(countryCode: "NL", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "nl")
  }

  @Test
  func `Chile → cl`() {
    let region = RegionSelection(countryCode: "CL", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "cl")
  }

  @Test
  func `Chile preset carries the expected radio parameters`() throws {
    let preset = try #require(RadioPresets.all.first(where: { $0.id == "cl" }))
    #expect(preset.frequencyMHz == 927.875)
    #expect(preset.bandwidthKHz == 62.5)
    #expect(preset.spreadingFactor == 8)
    #expect(preset.codingRate == 5)
    #expect(preset.region == .southAmerica)
  }

  // MARK: - Tier 3 (continent)

  @Test
  func `Berlin (DE) → eu-narrow (priority 110 beats eu-lr)`() {
    let region = RegionSelection(countryCode: "DE", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "eu-narrow")
  }

  // MARK: - No match

  @Test
  func `Bermuda → nil (no continent mapping)`() {
    let region = RegionSelection(countryCode: "BM", source: .manual)
    #expect(RadioPresets.recommended(for: region) == nil)
  }

  // MARK: - presets(for:)

  @Test
  func `presets(for: Sacramento) includes wcmesh in alternatives`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                 countyKey: "sacramento", source: .location)
    let ids = RadioPresets.presets(for: region).map(\.id)
    #expect(ids.contains("wcmesh"))
    #expect(ids.contains("us-ca"))
  }

  @Test
  func `presets(for: DE) returns continent-tier presets`() {
    let region = RegionSelection(countryCode: "DE", source: .location)
    let ids = RadioPresets.presets(for: region).map(\.id)
    #expect(ids.contains("eu-narrow"))
    #expect(ids.contains("eu-lr"))
    #expect(!ids.contains("us-ca"))
  }

  @Test
  func `presets(for: PT) returns country-and-below only`() {
    let region = RegionSelection(countryCode: "PT", source: .location)
    let ids = RadioPresets.presets(for: region).map(\.id)
    #expect(ids.contains("pt-868"))
    #expect(ids.contains("pt-433"))
    #expect(!ids.contains("eu-narrow"))
  }

  @Test
  func `presets(for: VN) includes both vn-narrow and vn`() {
    let region = RegionSelection(countryCode: "VN", source: .location)
    let ids = RadioPresets.presets(for: region).map(\.id)
    #expect(ids.contains("vn-narrow"))
    #expect(ids.contains("vn"))
  }
}

@Suite("RadioPresets.isSelectable(_:in:)")
struct RadioPresetSelectabilityTests {
  private func preset(_ id: String) -> RadioPreset {
    guard let preset = RadioPresets.all.first(where: { $0.id == id }) else {
      fatalError("missing preset \(id)")
    }
    return preset
  }

  // MARK: - County-restricted (WCMesh)

  @Test
  func `SoCal county → WCMesh selectable`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                 countyKey: "los angeles", source: .location)
    #expect(RadioPresets.isSelectable(preset("wcmesh"), in: region))
  }

  @Test
  func `NorCal county → WCMesh hidden, us-ca still selectable`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                 countyKey: "sacramento", source: .location)
    #expect(!RadioPresets.isSelectable(preset("wcmesh"), in: region))
    #expect(RadioPresets.isSelectable(preset("us-ca"), in: region))
  }

  @Test
  func `California with no county → WCMesh hidden`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA", source: .manual)
    #expect(!RadioPresets.isSelectable(preset("wcmesh"), in: region))
  }

  @Test
  func `Non-CA US state → WCMesh hidden, us-ca selectable`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-TX", source: .location)
    #expect(!RadioPresets.isSelectable(preset("wcmesh"), in: region))
    #expect(RadioPresets.isSelectable(preset("us-ca"), in: region))
  }

  @Test
  func `nil region → WCMesh hidden, global presets selectable`() {
    #expect(!RadioPresets.isSelectable(preset("wcmesh"), in: nil))
    #expect(RadioPresets.isSelectable(preset("us-ca"), in: nil))
    #expect(RadioPresets.isSelectable(preset("eu-narrow"), in: nil))
  }

  // MARK: - Non-county presets are never gated

  @Test
  func `Continent/country presets selectable for any region including nil`() {
    let regions: [RegionSelection?] = [
      nil,
      RegionSelection(countryCode: "US", administrativeAreaCode: "US-TX", source: .location),
      RegionSelection(countryCode: "DE", source: .location),
    ]
    for region in regions {
      #expect(RadioPresets.isSelectable(preset("eu-narrow"), in: region))
      #expect(RadioPresets.isSelectable(preset("us-ca"), in: region))
    }
  }
}

@Suite("RadioPreset protocol encoding")
struct RadioPresetEncodingTests {
  private func preset(frequencyMHz: Double, bandwidthKHz: Double) -> RadioPreset {
    RadioPreset(
      id: "test",
      name: "Test",
      region: .northAmerica,
      frequencyMHz: frequencyMHz,
      bandwidthKHz: bandwidthKHz,
      spreadingFactor: 7,
      codingRate: 5,
      availability: .continent(.northAmerica)
    )
  }

  @Test
  func `frequencyKHz rounds to the nearest kHz`() {
    // Truncation would yield 512001; rounding restores the representable 512002.
    #expect(preset(frequencyMHz: 512.002, bandwidthKHz: 62.5).frequencyKHz == 512_002)
  }

  @Test
  func `bandwidthHz rounds to the nearest Hz`() {
    #expect(preset(frequencyMHz: 915.0, bandwidthKHz: 62.501).bandwidthHz == 62501)
  }
}
