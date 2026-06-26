import MC1Services
import SwiftUI

/// Owns the device-local "last shown What's New version" baseline and decides once
/// per launch whether to present the sheet. Mirrors the `OnboardingState` idiom.
@Observable
@MainActor
final class WhatsNewState {

    private let defaults: UserDefaults

    /// The release to present, set by `evaluate` and cleared by `markShown`.
    var pendingRelease: WhatsNewRelease?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The launch-time show/suppress decision as a pure function, testable without
    /// `@MainActor` or `UserDefaults`. Returns the release to show, or `nil`.
    static func resolve(
        current: WhatsNewVersion,
        baselineString: String?,
        isOnboarded: Bool,
        catalog: [WhatsNewRelease]
    ) -> WhatsNewRelease? {
        guard let release = catalog.first(where: { $0.version == current }),
              !release.items.isEmpty else {
            return nil
        }
        guard let baselineString else {
            // No baseline: an upgrader sees the notes, a brand-new install (still
            // mid-onboarding) does not. The caller records the baseline either way.
            return isOnboarded ? release : nil
        }
        guard let baseline = WhatsNewVersion(marketingVersion: baselineString) else {
            return nil
        }
        return current > baseline ? release : nil
    }

    /// Runs the resolver once at launch. A show defers the baseline to `markShown`; a
    /// suppress finalizes it now so the sheet never re-appears. An unparseable version
    /// fails closed (neither presented nor recorded); screenshot mode is skipped.
    func evaluate(
        isOnboarded: Bool,
        isScreenshotMode: Bool,
        currentVersionString: String = Bundle.main.appVersion,
        catalog: [WhatsNewRelease] = WhatsNewCatalog.releases
    ) {
        guard !isScreenshotMode else { return }
        guard let current = WhatsNewVersion(marketingVersion: currentVersionString) else { return }

        let baselineString = defaults.string(forKey: AppStorageKey.lastShownWhatsNewVersion.rawValue)
        if let release = Self.resolve(
            current: current,
            baselineString: baselineString,
            isOnboarded: isOnboarded,
            catalog: catalog
        ) {
            pendingRelease = release
        } else {
            defaults.set(currentVersionString, forKey: AppStorageKey.lastShownWhatsNewVersion.rawValue)
        }
    }

    /// Persists the baseline for the running version and clears `pendingRelease`.
    /// Both swipe-to-dismiss and Continue route through here.
    func markShown() {
        defaults.set(Bundle.main.appVersion, forKey: AppStorageKey.lastShownWhatsNewVersion.rawValue)
        pendingRelease = nil
    }
}
