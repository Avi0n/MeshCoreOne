import MC1Services
import SwiftUI

/// Owns the device-local "last shown What's New build" baseline and decides once
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
        currentBuild: Int,
        baseline: Int?,
        isOnboarded: Bool,
        catalog: [WhatsNewRelease]
    ) -> WhatsNewRelease? {
        guard let release = catalog.first(where: { $0.build == currentBuild }),
              !release.items.isEmpty else {
            return nil
        }
        guard let baseline else {
            // No baseline: an upgrader sees the notes, a brand-new install (still
            // mid-onboarding) does not. The caller records the baseline either way.
            return isOnboarded ? release : nil
        }
        return currentBuild > baseline ? release : nil
    }

    /// Runs the resolver once at launch. A show defers the baseline to `markShown`; a
    /// suppress finalizes it now so the sheet never re-appears. An unparseable build
    /// fails closed (neither presented nor recorded); screenshot mode is skipped.
    func evaluate(
        isOnboarded: Bool,
        isScreenshotMode: Bool,
        currentBuildString: String = Bundle.main.appBuild,
        catalog: [WhatsNewRelease] = WhatsNewCatalog.releases
    ) {
        guard !isScreenshotMode else { return }
        guard let currentBuild = Int(currentBuildString) else { return }

        let baseline = defaults.string(forKey: AppStorageKey.lastShownWhatsNewBuild.rawValue).flatMap(Int.init)
        if let release = Self.resolve(
            currentBuild: currentBuild,
            baseline: baseline,
            isOnboarded: isOnboarded,
            catalog: catalog
        ) {
            pendingRelease = release
        } else {
            defaults.set(currentBuildString, forKey: AppStorageKey.lastShownWhatsNewBuild.rawValue)
        }
    }

    /// Persists the baseline for the running build and clears `pendingRelease`.
    /// Both swipe-to-dismiss and Continue route through here.
    func markShown() {
        defaults.set(Bundle.main.appBuild, forKey: AppStorageKey.lastShownWhatsNewBuild.rawValue)
        pendingRelease = nil
    }
}
