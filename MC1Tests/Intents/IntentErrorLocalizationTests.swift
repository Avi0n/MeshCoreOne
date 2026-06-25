import Foundation
import Testing
@testable import MC1
@testable import MC1Services

/// `IntentError.errorDescription` is the localization seam Siri and Shortcuts
/// read, so it must route through `L10n` in every locale, never an English
/// fallback. These tests pin every shipped case to its `L10n` key and confirm
/// the raw key resolves to real copy in all 9 locales.
struct IntentErrorLocalizationTests {

    /// The localizable table backing the `error.intent.*` keys.
    private static let table = "Localizable"

    /// Every `IntentError` case that carries its own L10n key, paired with the
    /// raw `.strings` key and the generated accessor that must agree with it.
    private static let casesWithKeys: [(error: IntentError, rawKey: String, generatedAccessor: String)] = [
        (.notConnected, "error.intent.notConnected", L10n.Localizable.Error.Intent.notConnected),
        (.invalidRecipient, "error.intent.invalidRecipient", L10n.Localizable.Error.Intent.invalidRecipient),
        (.messageTooLong, "error.intent.messageTooLong", L10n.Localizable.Error.Intent.messageTooLong),
        (.sendFailed, "error.intent.sendFailed", L10n.Localizable.Error.Intent.sendFailed),
        // `.advertFailed` reuses the existing advertisement-error copy rather than
        // minting a duplicate intent-scoped key.
        (.advertFailed, "error.advertisement.sendFailed", L10n.Localizable.Error.Advertisement.sendFailed),
    ]

    /// The 9 shipped locales. A key missing from any one would fall back to the
    /// raw key string at runtime, so each must resolve real copy.
    private static let locales = ["de", "en", "es", "fr", "nl", "pl", "ru", "uk", "zh-Hans"]

    // MARK: - Generated accessor agreement

    @Test func errorDescriptionRoutesThroughL10nForEachCase() {
        for entry in Self.casesWithKeys {
            #expect(entry.error.errorDescription == entry.generatedAccessor)
        }
    }

    // MARK: - sessionError recursion

    @Test func sessionErrorRecursesIntoWrappedMeshCoreError() {
        #expect(
            IntentError.sessionError(.timeout).errorDescription
                == L10n.Localizable.Error.MeshCore.timeout
        )
        #expect(
            IntentError.sessionError(.notConnected).errorDescription
                == MeshCoreError.notConnected.userFacingMessage
        )
    }

    @Test func sessionErrorCarriesNoKeyOfItsOwn() {
        // `.sessionError` must resolve to the wrapped error's copy, never the
        // raw key, in every locale (the wrapped error owns the localization).
        let resolved = IntentError.sessionError(.featureDisabled).errorDescription
        #expect(resolved == MeshCoreError.featureDisabled.userFacingMessage)
        #expect(resolved?.isEmpty == false)
    }

    // MARK: - No raw-key fallback across all 9 locales

    @Test func everyIntentKeyResolvesInEveryLocale() throws {
        for locale in Self.locales {
            let bundle = try #require(
                Self.localeBundle(locale),
                "Missing \(locale).lproj in the app bundle"
            )
            for entry in Self.casesWithKeys {
                let resolved = bundle.localizedString(
                    forKey: entry.rawKey,
                    value: Self.sentinel,
                    table: Self.table
                )
                #expect(
                    resolved != Self.sentinel && resolved != entry.rawKey,
                    "\(entry.rawKey) falls back to the raw key in \(locale).lproj"
                )
            }
        }
    }

    // MARK: - Helpers

    /// A value the bundle returns verbatim when the key is missing, so a
    /// missing key is distinguishable from real (possibly key-shaped) copy.
    private static let sentinel = "\u{0}__intent_key_missing__\u{0}"

    private static func localeBundle(_ locale: String) -> Bundle? {
        // The test host is the app, so Bundle.main carries every .lproj.
        guard let url = Bundle.main.url(forResource: locale, withExtension: "lproj") else {
            return nil
        }
        return Bundle(url: url)
    }
}
