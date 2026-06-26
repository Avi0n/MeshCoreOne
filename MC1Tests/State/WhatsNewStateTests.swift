import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@Suite("WhatsNewState")
@MainActor
final class WhatsNewStateTests {

    private let baselineKey = AppStorageKey.lastShownWhatsNewVersion.rawValue
    private let suiteName = "test.\(UUID().uuidString)"
    private let defaults: UserDefaults

    init() {
        defaults = UserDefaults(suiteName: suiteName)!
    }

    deinit {
        UserDefaults().removePersistentDomain(forName: suiteName)
    }

    private func version(_ string: String) -> WhatsNewVersion {
        WhatsNewVersion(marketingVersion: string)!
    }

    private func release(_ major: Int, _ minor: Int, items: Int = 1) -> WhatsNewRelease {
        WhatsNewRelease(
            version: WhatsNewVersion(major: major, minor: minor),
            items: (0..<items).map { WhatsNewItem(symbol: "star", title: "t\($0)", description: "d\($0)") }
        )
    }

    // MARK: - resolve matrix

    @Test("new install (not onboarded, no baseline) suppresses")
    func newInstallSuppresses() {
        let result = WhatsNewState.resolve(
            current: version("1.1"), baselineString: nil,
            isOnboarded: false, catalog: [release(1, 1)]
        )
        #expect(result == nil)
    }

    @Test("upgrader (onboarded, no baseline, entry exists) shows")
    func upgraderShows() {
        let result = WhatsNewState.resolve(
            current: version("1.1"), baselineString: nil,
            isOnboarded: true, catalog: [release(1, 1)]
        )
        #expect(result?.id == WhatsNewVersion(major: 1, minor: 1))
    }

    @Test("patch bump suppresses")
    func patchBumpSuppresses() {
        let result = WhatsNewState.resolve(
            current: version("1.0.2"), baselineString: "1.0",
            isOnboarded: true, catalog: [release(1, 0)]
        )
        #expect(result == nil)
    }

    @Test("minor bump shows")
    func minorBumpShows() {
        let result = WhatsNewState.resolve(
            current: version("1.1"), baselineString: "1.0",
            isOnboarded: true, catalog: [release(1, 1)]
        )
        #expect(result?.id == WhatsNewVersion(major: 1, minor: 1))
    }

    @Test("major bump shows")
    func majorBumpShows() {
        let result = WhatsNewState.resolve(
            current: version("2.0"), baselineString: "1.9",
            isOnboarded: true, catalog: [release(2, 0)]
        )
        #expect(result?.id == WhatsNewVersion(major: 2, minor: 0))
    }

    @Test("skipped version shows the current release's entry")
    func skippedVersionShowsCurrentEntry() {
        let result = WhatsNewState.resolve(
            current: version("1.2"), baselineString: "1.0",
            isOnboarded: true, catalog: [release(1, 1), release(1, 2)]
        )
        #expect(result?.id == WhatsNewVersion(major: 1, minor: 2))
    }

    @Test("no catalog entry for current version suppresses")
    func noEntrySuppresses() {
        let result = WhatsNewState.resolve(
            current: version("1.3"), baselineString: "1.0",
            isOnboarded: true, catalog: [release(1, 1), release(1, 2)]
        )
        #expect(result == nil)
    }

    @Test("matched entry with empty items suppresses")
    func emptyItemsSuppresses() {
        let result = WhatsNewState.resolve(
            current: version("1.1"), baselineString: nil,
            isOnboarded: true, catalog: [release(1, 1, items: 0)]
        )
        #expect(result == nil)
    }

    @Test("re-launch after a show (baseline equals current) does not re-show")
    func reLaunchDoesNotReShow() {
        let result = WhatsNewState.resolve(
            current: version("1.1"), baselineString: "1.1",
            isOnboarded: true, catalog: [release(1, 1)]
        )
        #expect(result == nil)
    }

    // MARK: - evaluate side effects

    @Test("screenshot mode neither shows nor writes a baseline")
    func screenshotModeSkips() {
        let state = WhatsNewState(defaults: defaults)

        state.evaluate(
            isOnboarded: true, isScreenshotMode: true,
            currentVersionString: "1.1", catalog: [release(1, 1)]
        )

        #expect(state.pendingRelease == nil)
        #expect(defaults.string(forKey: baselineKey) == nil)
    }

    @Test("show path sets pendingRelease and defers the baseline to markShown")
    func showPathSetsPendingReleaseWithoutBaseline() {
        let state = WhatsNewState(defaults: defaults)

        state.evaluate(
            isOnboarded: true, isScreenshotMode: false,
            currentVersionString: "1.1", catalog: [release(1, 1)]
        )

        #expect(state.pendingRelease?.id == WhatsNewVersion(major: 1, minor: 1))
        #expect(defaults.string(forKey: baselineKey) == nil)
    }

    @Test("suppress path finalizes the baseline immediately")
    func suppressPathFinalizesBaseline() {
        let state = WhatsNewState(defaults: defaults)

        state.evaluate(
            isOnboarded: true, isScreenshotMode: false,
            currentVersionString: "1.1", catalog: []
        )

        #expect(state.pendingRelease == nil)
        #expect(defaults.string(forKey: baselineKey) == "1.1")
    }

    @Test("unparseable version fails closed: neither shows nor writes a baseline")
    func unparseableFailsClosed() {
        let state = WhatsNewState(defaults: defaults)

        state.evaluate(
            isOnboarded: true, isScreenshotMode: false,
            currentVersionString: "unknown", catalog: [release(1, 1)]
        )

        #expect(state.pendingRelease == nil)
        #expect(defaults.string(forKey: baselineKey) == nil)
    }

    @Test("markShown persists the running version and clears the pending release")
    func markShownPersistsAndClears() {
        let state = WhatsNewState(defaults: defaults)
        state.pendingRelease = release(9, 9)

        state.markShown()

        #expect(state.pendingRelease == nil)
        #expect(defaults.string(forKey: baselineKey) == Bundle.main.appVersion)
    }
}
