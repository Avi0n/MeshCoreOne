import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@Suite("WhatsNewState")
@MainActor
final class WhatsNewStateTests {

    private let baselineKey = AppStorageKey.lastShownWhatsNewBuild.rawValue
    private let suiteName = "test.\(UUID().uuidString)"
    private let defaults: UserDefaults

    init() {
        defaults = UserDefaults(suiteName: suiteName)!
    }

    deinit {
        UserDefaults().removePersistentDomain(forName: suiteName)
    }

    private func release(_ build: Int, items: Int = 1) -> WhatsNewRelease {
        WhatsNewRelease(
            build: build,
            items: (0..<items).map { WhatsNewItem(symbol: "star", title: "t\($0)", description: "d\($0)") }
        )
    }

    // MARK: - resolve matrix

    @Test("new install (not onboarded, no baseline) suppresses")
    func newInstallSuppresses() {
        let result = WhatsNewState.resolve(
            currentBuild: 163, baseline: nil,
            isOnboarded: false, catalog: [release(163)]
        )
        #expect(result == nil)
    }

    @Test("upgrader (onboarded, no baseline, entry exists) shows")
    func upgraderShows() {
        let result = WhatsNewState.resolve(
            currentBuild: 163, baseline: nil,
            isOnboarded: true, catalog: [release(163)]
        )
        #expect(result?.id == 163)
    }

    @Test("build bump shows")
    func buildBumpShows() {
        let result = WhatsNewState.resolve(
            currentBuild: 163, baseline: 160,
            isOnboarded: true, catalog: [release(163)]
        )
        #expect(result?.id == 163)
    }

    @Test("no catalog entry for current build suppresses")
    func noEntrySuppresses() {
        let result = WhatsNewState.resolve(
            currentBuild: 164, baseline: 160,
            isOnboarded: true, catalog: [release(162), release(163)]
        )
        #expect(result == nil)
    }

    @Test("matched entry with empty items suppresses")
    func emptyItemsSuppresses() {
        let result = WhatsNewState.resolve(
            currentBuild: 163, baseline: nil,
            isOnboarded: true, catalog: [release(163, items: 0)]
        )
        #expect(result == nil)
    }

    @Test("re-launch after a show (baseline equals current) does not re-show")
    func reLaunchDoesNotReShow() {
        let result = WhatsNewState.resolve(
            currentBuild: 163, baseline: 163,
            isOnboarded: true, catalog: [release(163)]
        )
        #expect(result == nil)
    }

    // MARK: - evaluate side effects

    @Test("screenshot mode neither shows nor writes a baseline")
    func screenshotModeSkips() {
        let state = WhatsNewState(defaults: defaults)

        state.evaluate(
            isOnboarded: true, isScreenshotMode: true,
            currentBuildString: "163", catalog: [release(163)]
        )

        #expect(state.pendingRelease == nil)
        #expect(defaults.string(forKey: baselineKey) == nil)
    }

    @Test("show path sets pendingRelease and defers the baseline to markShown")
    func showPathSetsPendingReleaseWithoutBaseline() {
        let state = WhatsNewState(defaults: defaults)

        state.evaluate(
            isOnboarded: true, isScreenshotMode: false,
            currentBuildString: "163", catalog: [release(163)]
        )

        #expect(state.pendingRelease?.id == 163)
        #expect(defaults.string(forKey: baselineKey) == nil)
    }

    @Test("suppress path finalizes the baseline immediately")
    func suppressPathFinalizesBaseline() {
        let state = WhatsNewState(defaults: defaults)

        state.evaluate(
            isOnboarded: true, isScreenshotMode: false,
            currentBuildString: "163", catalog: []
        )

        #expect(state.pendingRelease == nil)
        #expect(defaults.string(forKey: baselineKey) == "163")
    }

    @Test("unparseable build fails closed: neither shows nor writes a baseline")
    func unparseableFailsClosed() {
        let state = WhatsNewState(defaults: defaults)

        state.evaluate(
            isOnboarded: true, isScreenshotMode: false,
            currentBuildString: "unknown", catalog: [release(163)]
        )

        #expect(state.pendingRelease == nil)
        #expect(defaults.string(forKey: baselineKey) == nil)
    }

    @Test("markShown persists the running build and clears the pending release")
    func markShownPersistsAndClears() {
        let state = WhatsNewState(defaults: defaults)
        state.pendingRelease = release(99)

        state.markShown()

        #expect(state.pendingRelease == nil)
        #expect(defaults.string(forKey: baselineKey) == Bundle.main.appBuild)
    }
}
