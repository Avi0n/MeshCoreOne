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

  @Test
  func `Pennsylvania → phillymesh`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-PA", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "phillymesh")
  }

  @Test
  func `New Jersey → phillymesh`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-NJ", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "phillymesh")
  }

  @Test
  func `Delaware → phillymesh`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-DE", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "phillymesh")
  }

  @Test
  func `Maryland → phillymesh`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-MD", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "phillymesh")
  }

  @Test
  func `Manual US with no admin → us-ca`() {
    let region = RegionSelection(countryCode: "US", source: .manual)
    #expect(RadioPresets.recommended(for: region)?.id == "us-ca")
  }

  @Test
  func `PhillyMesh preset carries the expected radio parameters`() throws {
    let preset = try #require(RadioPresets.all.first(where: { $0.id == "phillymesh" }))
    #expect(preset.name == "PhillyMesh")
    #expect(preset.frequencyMHz == 902.250)
    #expect(preset.bandwidthKHz == 500)
    #expect(preset.spreadingFactor == 11)
    #expect(preset.codingRate == 5)
    #expect(preset.region == .northAmerica)
  }

  @Test
  func `USA catalog name is USA`() throws {
    let preset = try #require(RadioPresets.all.first(where: { $0.id == "us-ca" }))
    #expect(preset.name == "USA")
  }

  @Test
  func `Canada → ca`() {
    let region = RegionSelection(countryCode: "CA", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "ca")
  }

  @Test
  func `Canada preset carries the expected radio parameters`() throws {
    let preset = try #require(RadioPresets.all.first(where: { $0.id == "ca" }))
    #expect(preset.name == "Canada")
    #expect(preset.frequencyMHz == 910.525)
    #expect(preset.bandwidthKHz == 62.5)
    #expect(preset.spreadingFactor == 7)
    #expect(preset.codingRate == 5)
    #expect(preset.pathHashSize == 3)
    #expect(preset.region == .northAmerica)
  }

  @Test
  func `Limburg preset carries the expected radio parameters`() throws {
    let preset = try #require(RadioPresets.all.first(where: { $0.id == "nl-li" }))
    #expect(preset.name == "Netherlands (Limburg)")
    #expect(preset.frequencyMHz == 869.618)
    #expect(preset.bandwidthKHz == 62.5)
    #expect(preset.spreadingFactor == 8)
    #expect(preset.codingRate == 8)
    #expect(preset.pathHashSize == 2)
    #expect(preset.region == .europe)
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
  func `Hungary, Slovakia, and Costa Rica recommend their country presets`() {
    #expect(RadioPresets.recommended(for: RegionSelection(countryCode: "HU", source: .location))?.id == "hu")
    #expect(RadioPresets.recommended(for: RegionSelection(countryCode: "SK", source: .location))?.id == "sk")
    #expect(RadioPresets.recommended(for: RegionSelection(countryCode: "CR", source: .location))?.id == "cr")
  }

  @Test
  func `catalog pathHashSize is set only on presets that name a hash`() throws {
    func size(_ id: String) throws -> Int? {
      try #require(RadioPresets.all.first(where: { $0.id == id })).pathHashSize
    }
    #expect(try size("hu") == 2)
    #expect(try size("sk") == 2)
    #expect(try size("nz-lr") == 1)
    #expect(try size("nz-narrow") == 2)
    #expect(try size("phillymesh") == 2)
    #expect(try size("ca") == 3)
    #expect(try size("wcmesh") == 3)
    #expect(try size("nl-li") == 2)
    #expect(try size("nl") == nil)
    #expect(try size("us-ca") == nil)
    #expect(try size("cr") == nil)
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

  @Test
  func `Brazil → br`() {
    let region = RegionSelection(countryCode: "BR", source: .location)
    #expect(RadioPresets.recommended(for: region)?.id == "br")
  }

  @Test
  func `Brazil preset carries the expected radio parameters`() throws {
    let preset = try #require(RadioPresets.all.first(where: { $0.id == "br" }))
    #expect(preset.frequencyMHz == 923.125)
    #expect(preset.bandwidthKHz == 62.5)
    #expect(preset.spreadingFactor == 8)
    #expect(preset.codingRate == 8)
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
  func `presets(for: Texas) includes phillymesh in membership`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-TX", source: .location)
    let ids = RadioPresets.presets(for: region).map(\.id)
    #expect(ids.contains("phillymesh"))
    #expect(ids.contains("us-ca"))
    #expect(ids.contains("wcmesh"))
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

  @Test
  func `presets(for: BR) includes br`() {
    let region = RegionSelection(countryCode: "BR", source: .location)
    let ids = RadioPresets.presets(for: region).map(\.id)
    #expect(ids.contains("br"))
  }

  @Test
  func `presets(for: CA) includes ca and excludes us-ca`() {
    let region = RegionSelection(countryCode: "CA", source: .location)
    let ids = RadioPresets.presets(for: region).map(\.id)
    #expect(ids.contains("ca"))
    #expect(!ids.contains("us-ca"))
    #expect(!ids.contains("wcmesh"))
    #expect(!ids.contains("phillymesh"))
  }

  @Test
  func `presets(for: NL) includes nl and nl-li`() {
    let region = RegionSelection(countryCode: "NL", source: .location)
    let ids = RadioPresets.presets(for: region).map(\.id)
    #expect(ids.contains("nl"))
    #expect(ids.contains("nl-li"))
    #expect(!ids.contains("eu-narrow"))
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
    #expect(!RadioPresets.isSelectable(preset("phillymesh"), in: nil))
    #expect(!RadioPresets.isSelectable(preset("au-qld"), in: nil))
    #expect(!RadioPresets.isSelectable(preset("au-sa-wa"), in: nil))
    #expect(RadioPresets.isSelectable(preset("us-ca"), in: nil))
    #expect(RadioPresets.isSelectable(preset("eu-narrow"), in: nil))
  }

  // MARK: - Sub-region presets (PhillyMesh, AU-QLD, AU-SA-WA)

  @Test
  func `SoCal county → WCMesh selectable, PhillyMesh hidden`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                 countyKey: "los angeles", source: .location)
    #expect(RadioPresets.isSelectable(preset("wcmesh"), in: region))
    #expect(!RadioPresets.isSelectable(preset("phillymesh"), in: region))
  }

  @Test
  func `Pennsylvania → PhillyMesh selectable, WCMesh hidden, us-ca selectable`() {
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-PA", source: .location)
    #expect(RadioPresets.isSelectable(preset("phillymesh"), in: region))
    #expect(!RadioPresets.isSelectable(preset("wcmesh"), in: region))
    #expect(RadioPresets.isSelectable(preset("us-ca"), in: region))
  }

  @Test
  func `Manual US with no admin → PhillyMesh hidden`() {
    let region = RegionSelection(countryCode: "US", source: .manual)
    #expect(!RadioPresets.isSelectable(preset("phillymesh"), in: region))
    #expect(RadioPresets.isSelectable(preset("us-ca"), in: region))
  }

  @Test
  func `nil region → PhillyMesh hidden, us-ca selectable`() {
    #expect(!RadioPresets.isSelectable(preset("phillymesh"), in: nil))
    #expect(RadioPresets.isSelectable(preset("us-ca"), in: nil))
  }

  @Test
  func `Queensland → au-qld selectable, au-sa-wa hidden`() {
    let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-QLD", source: .location)
    #expect(RadioPresets.isSelectable(preset("au-qld"), in: region))
    #expect(!RadioPresets.isSelectable(preset("au-sa-wa"), in: region))
  }

  @Test
  func `South Australia → au-sa-wa selectable, au-qld hidden`() {
    let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-SA", source: .location)
    #expect(RadioPresets.isSelectable(preset("au-sa-wa"), in: region))
    #expect(!RadioPresets.isSelectable(preset("au-qld"), in: region))
  }

  @Test
  func `Manual AU with no admin → both AU sub-region presets hidden, au-915 selectable`() {
    let region = RegionSelection(countryCode: "AU", source: .manual)
    #expect(!RadioPresets.isSelectable(preset("au-qld"), in: region))
    #expect(!RadioPresets.isSelectable(preset("au-sa-wa"), in: region))
    #expect(RadioPresets.isSelectable(preset("au-915"), in: region))
  }

  @Test
  func `Victoria → both AU sub-region presets hidden, au-915 selectable`() {
    let region = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-VIC", source: .location)
    #expect(!RadioPresets.isSelectable(preset("au-qld"), in: region))
    #expect(!RadioPresets.isSelectable(preset("au-sa-wa"), in: region))
    #expect(RadioPresets.isSelectable(preset("au-915"), in: region))
  }

  // MARK: - Country / continent presets stay ungated

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

@Suite("RadioPresets.visiblePresets(for:activeID:)")
struct RadioPresetVisiblePresetsTests {
  private func ids(_ region: RegionSelection?, activeID: String?) -> [String] {
    RadioPresets.visiblePresets(for: region, activeID: activeID).map(\.id)
  }

  @Test
  func `US-CA includes us-ca and excludes eu-narrow`() {
    let region = RegionSelection(
      countryCode: "US",
      administrativeAreaCode: "US-CA",
      source: .location
    )
    let visible = ids(region, activeID: nil)
    #expect(visible.contains("us-ca"))
    #expect(!visible.contains("eu-narrow"))
  }

  @Test
  func `US-CA with active eu-narrow keeps both and presets(for:) does not contain eu-narrow`() {
    let region = RegionSelection(
      countryCode: "US",
      administrativeAreaCode: "US-CA",
      source: .location
    )
    let visible = ids(region, activeID: "eu-narrow")
    #expect(visible.contains("us-ca"))
    #expect(visible.contains("eu-narrow"))
    #expect(!RadioPresets.presets(for: region).map(\.id).contains("eu-narrow"))
  }

  @Test
  func `WCMesh only when the county matches; us-ca remains for California without that county`() {
    let la = RegionSelection(
      countryCode: "US",
      administrativeAreaCode: "US-CA",
      countyKey: "los angeles",
      source: .location
    )
    #expect(ids(la, activeID: nil).contains("wcmesh"))
    #expect(ids(la, activeID: nil).contains("us-ca"))

    let california = RegionSelection(
      countryCode: "US",
      administrativeAreaCode: "US-CA",
      source: .manual
    )
    let californiaIDs = ids(california, activeID: nil)
    #expect(!californiaIDs.contains("wcmesh"))
    #expect(californiaIDs.contains("us-ca"))
  }

  @Test
  func `nil place uses locale list, hides county and sub-region presets, keeps country presets`() {
    let visible = ids(nil, activeID: nil)
    #expect(!visible.contains("wcmesh"))
    #expect(!visible.contains("phillymesh"))
    #expect(!visible.contains("au-qld"))
    #expect(visible.contains("us-ca"))
    #expect(visible.contains("eu-narrow"))
  }

  @Test
  func `Bermuda empty regional list falls back to locale list`() {
    let bermuda = RegionSelection(countryCode: "BM", source: .manual)
    #expect(Set(ids(bermuda, activeID: nil)) == Set(ids(nil, activeID: nil)))
    #expect(!ids(bermuda, activeID: nil).isEmpty)
  }
}

@Suite("RadioPresets alias identity")
struct RadioPresetAliasIdentityTests {
  private func rf(_ id: String) throws -> RadioPreset {
    try #require(RadioPresets.all.first(where: { $0.id == id }))
  }

  private func matches(of id: String) throws -> [String] {
    let preset = try rf(id)
    return RadioPresets.matchingPresets(
      frequencyKHz: preset.frequencyKHz,
      bandwidthKHz: preset.bandwidthHz,
      spreadingFactor: preset.spreadingFactor,
      codingRate: preset.codingRate
    ).map(\.id)
  }

  private func resolved(
    of id: String,
    preferredID: String?,
    region: RegionSelection?
  ) throws -> String? {
    let preset = try rf(id)
    return RadioPresets.resolvedPreset(
      frequencyKHz: preset.frequencyKHz,
      bandwidthKHz: preset.bandwidthHz,
      spreadingFactor: preset.spreadingFactor,
      codingRate: preset.codingRate,
      preferredID: preferredID,
      region: region
    )?.id
  }

  @Test
  func `Brazil RF matches au-sa-wa and br in catalog order`() throws {
    #expect(try matches(of: "br") == ["au-sa-wa", "br"])
  }

  @Test
  func `EU Narrow RF matches eu-narrow, ch, and nl-li in catalog order`() throws {
    #expect(try matches(of: "ch") == ["eu-narrow", "ch", "nl-li"])
  }

  @Test
  func `Netherlands RF matches hu, nl, and sk in catalog order`() throws {
    #expect(try matches(of: "nl") == ["hu", "nl", "sk"])
  }

  @Test
  func `unlabeled NL-family collision uses country recommendation`() throws {
    let hu = RegionSelection(countryCode: "HU", source: .location)
    let nl = RegionSelection(countryCode: "NL", source: .location)
    let sk = RegionSelection(countryCode: "SK", source: .location)
    let de = RegionSelection(countryCode: "DE", source: .location)
    #expect(try resolved(of: "nl", preferredID: nil, region: hu) == "hu")
    #expect(try resolved(of: "nl", preferredID: nil, region: nl) == "nl")
    #expect(try resolved(of: "nl", preferredID: nil, region: sk) == "sk")
    #expect(try resolved(of: "nl", preferredID: nil, region: de) == nil)
  }

  @Test
  func `unique RF still returns a one-element set`() throws {
    #expect(try matches(of: "au-qld") == ["au-qld"])
    #expect(try matches(of: "phillymesh") == ["phillymesh"])
  }

  @Test
  func `USA RF matches us-ca and ca in catalog order`() throws {
    #expect(try matches(of: "us-ca") == ["us-ca", "ca"])
  }

  @Test
  func `preferredID wins among aliases`() throws {
    #expect(try resolved(of: "br", preferredID: "br", region: nil) == "br")
    #expect(try resolved(of: "br", preferredID: "au-sa-wa", region: nil) == "au-sa-wa")
    #expect(try resolved(of: "ch", preferredID: "ch", region: nil) == "ch")
  }

  @Test
  func `stale preferredID does not win`() throws {
    #expect(try resolved(of: "br", preferredID: "us-ca", region: nil) == nil)
  }

  @Test
  func `stale preferredID on unique RF yields that unique preset`() throws {
    #expect(try resolved(of: "au-qld", preferredID: "br", region: nil) == "au-qld")
  }

  @Test
  func `stale preferredID on USA/Canada RF uses recommendation or Custom`() throws {
    let us = RegionSelection(countryCode: "US", source: .manual)
    let ca = RegionSelection(countryCode: "CA", source: .location)
    #expect(try resolved(of: "us-ca", preferredID: "br", region: us) == "us-ca")
    #expect(try resolved(of: "us-ca", preferredID: "br", region: ca) == "ca")
    #expect(try resolved(of: "us-ca", preferredID: "br", region: nil) == nil)
  }

  @Test
  func `unlabeled collision uses recommended when it is in the set`() throws {
    let auSA = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-SA", source: .location)
    let br = RegionSelection(countryCode: "BR", source: .location)
    let ch = RegionSelection(countryCode: "CH", source: .location)
    let de = RegionSelection(countryCode: "DE", source: .location)
    #expect(try resolved(of: "br", preferredID: nil, region: auSA) == "au-sa-wa")
    #expect(try resolved(of: "br", preferredID: nil, region: br) == "br")
    #expect(try resolved(of: "ch", preferredID: nil, region: ch) == "ch")
    #expect(try resolved(of: "ch", preferredID: nil, region: de) == "eu-narrow")
  }

  @Test
  func `unlabeled collision with no recommended in set is Custom`() throws {
    let us = RegionSelection(countryCode: "US", source: .manual)
    #expect(try resolved(of: "br", preferredID: nil, region: us) == nil)
    #expect(try resolved(of: "br", preferredID: nil, region: nil) == nil)
  }

  @Test
  func `preferredID beats recommended`() throws {
    let auSA = RegionSelection(countryCode: "AU", administrativeAreaCode: "AU-SA", source: .location)
    #expect(try resolved(of: "br", preferredID: "br", region: auSA) == "br")
  }

  @Test
  func `matchingPreset is unique-or-nil, not first-wins`() throws {
    let br = try rf("br")
    #expect(
      RadioPresets.matchingPreset(
        frequencyKHz: br.frequencyKHz,
        bandwidthKHz: br.bandwidthHz,
        spreadingFactor: br.spreadingFactor,
        codingRate: br.codingRate
      ) == nil
    )
    let qld = try rf("au-qld")
    #expect(
      RadioPresets.matchingPreset(
        frequencyKHz: qld.frequencyKHz,
        bandwidthKHz: qld.bandwidthHz,
        spreadingFactor: qld.spreadingFactor,
        codingRate: qld.codingRate
      )?.id == "au-qld"
    )
  }

  @Test
  func `alreadyConfigured is RF membership not first-match id`() throws {
    let br = try rf("br")
    let matches = RadioPresets.matchingPresets(
      frequencyKHz: br.frequencyKHz,
      bandwidthKHz: br.bandwidthHz,
      spreadingFactor: br.spreadingFactor,
      codingRate: br.codingRate
    )
    #expect(matches.contains { $0.id == "br" })
    #expect(RadioPresets.matchingPreset(
      frequencyKHz: br.frequencyKHz,
      bandwidthKHz: br.bandwidthHz,
      spreadingFactor: br.spreadingFactor,
      codingRate: br.codingRate
    )?.id != "br")
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

@Suite("RadioPreset path hash size")
struct RadioPresetPathHashSizeTests {
  private func preset(pathHashSize: Int?) -> RadioPreset {
    RadioPreset(
      id: "test",
      name: "Test",
      region: .europe,
      frequencyMHz: 869.618,
      bandwidthKHz: 62.5,
      spreadingFactor: 7,
      codingRate: 5,
      pathHashSize: pathHashSize,
      availability: .countries(["NL"])
    )
  }

  @Test
  func `pathHashMode is pathHashSize minus one, or nil`() {
    #expect(preset(pathHashSize: nil).pathHashMode == nil)
    #expect(preset(pathHashSize: 1).pathHashMode == 0)
    #expect(preset(pathHashSize: 2).pathHashMode == 1)
  }
}
