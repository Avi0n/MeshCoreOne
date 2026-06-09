import Foundation

/// Geographic catalog used to translate `CLPlacemark` results into the
/// `RegionSelection` keys consumed by `RadioPresets.recommended(for:)`.
///
/// Vocabulary note: the existing `RegionDiscoveryService` uses "region" to
/// mean a *firmware mesh region* (named flood-routing scope on a repeater).
/// This file's "region" vocabulary refers to *user geographic location*.
public enum RegionalAreas {

    public struct Country: Sendable, Identifiable {
        public let id: String                   // ISO α-2
        public let subdivisions: [Subdivision]?

        public var localizedName: String {
            Locale.current.localizedString(forRegionCode: id) ?? id
        }
    }

    public struct Subdivision: Sendable, Identifiable {
        public let id: String                   // ISO 3166-2 code
        public let normalizedNames: Set<String> // lowercased, diacritic-folded matchers from CLPlacemark
        public let nameKey: String              // L10n key under Settings.strings

        public init(id: String, normalizedNames: Set<String>, nameKey: String) {
            self.id = id
            self.normalizedNames = normalizedNames
            self.nameKey = nameKey
        }
    }

    public static let usSubdivisions: [Subdivision] = [
        Subdivision(id: "US-CA",
                    normalizedNames: ["california", "ca"],
                    nameKey: "region.subdivision.US-CA"),
    ]

    public static let auSubdivisions: [Subdivision] = [
        Subdivision(id: "AU-QLD",
                    normalizedNames: ["queensland", "qld"],
                    nameKey: "region.subdivision.AU-QLD"),
        Subdivision(id: "AU-SA",
                    normalizedNames: ["south australia", "sa"],
                    nameKey: "region.subdivision.AU-SA"),
        Subdivision(id: "AU-WA",
                    normalizedNames: ["western australia", "wa"],
                    nameKey: "region.subdivision.AU-WA"),
    ]

    /// ISO α-2 → `RadioRegion` mapping. Mexico (MX), Africa, and
    /// South America are intentionally absent — these countries fall through
    /// `recommended(for:)` to the empty-region fallback.
    public static let continents: [String: RadioRegion] = [
        // North America
        "US": .northAmerica, "CA": .northAmerica,
        // Europe
        "GB": .europe, "IE": .europe, "DE": .europe, "FR": .europe,
        "IT": .europe, "ES": .europe, "PT": .europe, "NL": .europe,
        "BE": .europe, "CH": .europe, "AT": .europe, "CZ": .europe,
        "PL": .europe, "DK": .europe, "SE": .europe, "NO": .europe,
        "FI": .europe, "GR": .europe, "HU": .europe, "RO": .europe,
        // Oceania
        "AU": .oceania, "NZ": .oceania,
        // Asia
        "VN": .asia, "TH": .asia, "MY": .asia, "SG": .asia,
        "PH": .asia, "ID": .asia, "JP": .asia, "KR": .asia,
    ]

    /// `countries` sorted by `localizedName` once at first access. The order freezes for
    /// the process lifetime — acceptable because iOS app-language changes require relaunch,
    /// so the user never sees a stale order in practice.
    public static let countriesSortedByLocalizedName: [Country] = countries.sorted {
        $0.localizedName < $1.localizedName
    }

    public static let countries: [Country] = [
        Country(id: "US", subdivisions: usSubdivisions),
        Country(id: "CA", subdivisions: nil),
        Country(id: "AU", subdivisions: auSubdivisions),
        Country(id: "NZ", subdivisions: nil),
        Country(id: "GB", subdivisions: nil),
        Country(id: "IE", subdivisions: nil),
        Country(id: "DE", subdivisions: nil),
        Country(id: "FR", subdivisions: nil),
        Country(id: "IT", subdivisions: nil),
        Country(id: "ES", subdivisions: nil),
        Country(id: "PT", subdivisions: nil),
        Country(id: "NL", subdivisions: nil),
        Country(id: "BE", subdivisions: nil),
        Country(id: "CH", subdivisions: nil),
        Country(id: "AT", subdivisions: nil),
        Country(id: "CZ", subdivisions: nil),
        Country(id: "PL", subdivisions: nil),
        Country(id: "DK", subdivisions: nil),
        Country(id: "SE", subdivisions: nil),
        Country(id: "NO", subdivisions: nil),
        Country(id: "FI", subdivisions: nil),
        Country(id: "GR", subdivisions: nil),
        Country(id: "HU", subdivisions: nil),
        Country(id: "RO", subdivisions: nil),
        Country(id: "VN", subdivisions: nil),
        Country(id: "TH", subdivisions: nil),
        Country(id: "MY", subdivisions: nil),
        Country(id: "SG", subdivisions: nil),
        Country(id: "PH", subdivisions: nil),
        Country(id: "ID", subdivisions: nil),
        Country(id: "JP", subdivisions: nil),
        Country(id: "KR", subdivisions: nil),
    ]

    /// Normalized US county names (lowercased, diacritic-folded, "county" suffix stripped),
    /// indexed by ISO 3166-2 state code. Only states with county-scoped presets are filled.
    public static let usCounties: [String: Set<String>] = [
        "US-CA": [
            "los angeles", "orange", "san diego", "riverside", "san bernardino",
            "ventura", "imperial", "kern", "santa barbara", "san luis obispo",
        ],
    ]

    /// Returns the subdivisions catalog for an ISO α-2 country code, or an empty array
    /// when the country has no sub-region presets (or the code is unknown).
    public static func subdivisions(for country: String?) -> [Subdivision] {
        guard let country,
              let entry = countries.first(where: { $0.id == country }) else { return [] }
        return entry.subdivisions ?? []
    }

    /// Returns the ISO 3166-2 subdivision code matching a normalized administrative area name.
    /// Matches against `Subdivision.normalizedNames`, which contains both the English long form
    /// (e.g. "california") and short codes (e.g. "ca") so `CLPlacemark.administrativeArea`
    /// returning either form resolves correctly. Returns nil on miss; recommendation falls
    /// to country tier.
    public static func matchSubdivision(country: String, normalized: String?) -> String? {
        guard let normalized,
              let entry = countries.first(where: { $0.id == country }),
              let subdivisions = entry.subdivisions else { return nil }
        return subdivisions.first(where: { $0.normalizedNames.contains(normalized) })?.id
    }

    /// Returns the normalized county key when (country, state, name) all match the catalog.
    /// US counties have no ISO identifier — the normalized name *is* the key.
    public static func matchCounty(country: String, state: String?, normalized: String?) -> String? {
        guard country == "US",
              let state,
              let normalized,
              let countiesForState = usCounties[state],
              countiesForState.contains(normalized) else { return nil }
        return normalized
    }

    /// Returns a localized display name for Settings detail and Radio footer.
    /// Short form for unambiguous US states; disambiguated "State, Country" for ambiguous regions.
    public static func displayName(for region: RegionSelection) -> String {
        let countryName = Locale.current.localizedString(forRegionCode: region.countryCode) ?? region.countryCode
        guard let admin = region.administrativeAreaCode else { return countryName }
        let stateName = subdivisionDisplayName(admin) ?? admin
        if region.countryCode == "US" || region.countryCode == "CA" {
            return stateName
        }
        return "\(stateName), \(countryName)"
    }

    /// Returns the localized subdivision name for an ISO 3166-2 code (e.g. "US-CA" → "California").
    /// Looks up `region.subdivision.<code>` in the host app bundle's `Settings.strings` and falls
    /// back to the English value when the key is missing, so unit tests running outside an app
    /// bundle still resolve a deterministic name.
    public static func subdivisionDisplayName(_ code: String) -> String? {
        guard let englishFallback = englishSubdivisionFallbacks[code] else { return nil }
        let key = "region.subdivision.\(code)"
        return Bundle.main.localizedString(forKey: key, value: englishFallback, table: "Settings")
    }

    /// English values used as the `value:` fallback in `bundle.localizedString` and as the source
    /// of truth for `Settings.strings` `region.subdivision.*` entries.
    private static let englishSubdivisionFallbacks: [String: String] = [
        "US-CA": "California",
        "AU-QLD": "Queensland",
        "AU-SA": "South Australia",
        "AU-WA": "Western Australia",
    ]
}
