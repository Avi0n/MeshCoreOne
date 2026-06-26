import Foundation
import Testing
@testable import MC1

/// App Intents resolves every static metadata literal (`LocalizedStringResource`
/// title, description, parameter title, short title, and `ParameterSummary`)
/// against the `Tools` table in Siri's locale, so a key missing from any one of
/// the 9 shipped `.lproj` bundles surfaces as the raw key in that language. These
/// tests pin every metadata key the intents reference and confirm each resolves
/// to real copy in all 9 locales, never the raw-key fallback.
struct IntentMetadataLocalizationTests {

    /// The table backing every App Intents static metadata literal.
    private static let table = "Tools"

    /// Every key passed to `LocalizedStringResource("...", table: "Tools")` by the
    /// Intents sources. Each is a dotted identifier, so a value equal to the key
    /// signals a missing translation. The `ParameterSummary` keys are checked
    /// separately because their English value legitimately equals the key string.
    private static let keys: [String] = [
        "intent.entity.target",
        "intent.entity.channel",
        "intent.status.title",
        "intent.status.shortTitle",
        "intent.status.description",
        "intent.send.title",
        "intent.send.shortTitle",
        "intent.send.description",
        "intent.send.param.target",
        "intent.send.param.message",
        "intent.advert.title",
        "intent.advert.shortTitle",
        "intent.advert.description",
        "intent.advert.param.reach",
        "intent.advert.reach.type",
        "intent.advert.reach.zeroHop",
        "intent.advert.reach.flood",
    ]

    /// The 9 shipped locales. A key missing from any one falls back to the raw
    /// key string at runtime, so each must resolve real copy.
    private static let locales = ["de", "en", "es", "fr", "nl", "pl", "ru", "uk", "zh-Hans"]

    @Test func everyMetadataKeyResolvesInEveryLocale() throws {
        for locale in Self.locales {
            let bundle = try #require(
                Self.localeBundle(locale),
                "Missing \(locale).lproj in the app bundle"
            )
            for key in Self.keys {
                let resolved = bundle.localizedString(
                    forKey: key,
                    value: Self.sentinel,
                    table: Self.table
                )
                #expect(
                    resolved != Self.sentinel && resolved != key,
                    "\(key) falls back to the raw key in \(locale).lproj"
                )
            }
        }
    }

    /// The `ParameterSummary` format strings substitute `${message}` and the
    /// recipient token at runtime, so every locale's translation must keep both
    /// tokens or the displayed summary loses a value. This also proves the keys
    /// are present in every locale: a missing key resolves to the sentinel, which
    /// carries neither token, so the token assertion fails.
    @Test func parameterSummaryKeysKeepTheirTokensInEveryLocale() throws {
        let summaryTokens: [(key: String, tokens: [String])] = [
            ("Send ${message} to ${target}", ["${message}", "${target}"]),
            ("Send a ${reach} advert", ["${reach}"]),
        ]
        for locale in Self.locales {
            let bundle = try #require(Self.localeBundle(locale))
            for entry in summaryTokens {
                let resolved = bundle.localizedString(
                    forKey: entry.key,
                    value: Self.sentinel,
                    table: Self.table
                )
                for token in entry.tokens {
                    #expect(
                        resolved.contains(token),
                        "\(entry.key) drops \(token) in \(locale).lproj: \(resolved)"
                    )
                }
            }
        }
    }

    // MARK: - Helpers

    /// A value the bundle returns verbatim when the key is missing, so a missing
    /// key is distinguishable from real (possibly key-shaped) copy.
    private static let sentinel = "\u{0}__intent_metadata_key_missing__\u{0}"

    private static func localeBundle(_ locale: String) -> Bundle? {
        guard let url = Bundle.main.url(forResource: locale, withExtension: "lproj") else {
            return nil
        }
        return Bundle(url: url)
    }
}
