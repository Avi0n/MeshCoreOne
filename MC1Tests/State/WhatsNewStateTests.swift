import Foundation
@testable import MC1
@testable import MC1Services
import Testing

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
      items: (0..<items).map { WhatsNewItem(symbol: "star", title: "t\($0)", description: "d\($0)") },
      releaseNotesURL: URL(string: "https://example.com/releases")!
    )
  }

  // MARK: - resolve matrix

  @Test
  func `new install (not onboarded, no baseline) suppresses`() {
    let result = WhatsNewState.resolve(
      current: version("1.1"), baselineString: nil,
      isOnboarded: false, catalog: [release(1, 1)]
    )
    #expect(result == nil)
  }

  @Test
  func `upgrader (onboarded, no baseline, entry exists) shows`() {
    let result = WhatsNewState.resolve(
      current: version("1.1"), baselineString: nil,
      isOnboarded: true, catalog: [release(1, 1)]
    )
    #expect(result?.id == WhatsNewVersion(major: 1, minor: 1))
  }

  @Test
  func `patch bump suppresses`() {
    let result = WhatsNewState.resolve(
      current: version("1.0.2"), baselineString: "1.0",
      isOnboarded: true, catalog: [release(1, 0)]
    )
    #expect(result == nil)
  }

  @Test
  func `minor bump shows`() {
    let result = WhatsNewState.resolve(
      current: version("1.1"), baselineString: "1.0",
      isOnboarded: true, catalog: [release(1, 1)]
    )
    #expect(result?.id == WhatsNewVersion(major: 1, minor: 1))
  }

  @Test
  func `major bump shows`() {
    let result = WhatsNewState.resolve(
      current: version("2.0"), baselineString: "1.9",
      isOnboarded: true, catalog: [release(2, 0)]
    )
    #expect(result?.id == WhatsNewVersion(major: 2, minor: 0))
  }

  @Test
  func `skipped version shows the current release's entry`() {
    let result = WhatsNewState.resolve(
      current: version("1.2"), baselineString: "1.0",
      isOnboarded: true, catalog: [release(1, 1), release(1, 2)]
    )
    #expect(result?.id == WhatsNewVersion(major: 1, minor: 2))
  }

  @Test
  func `no catalog entry for current version suppresses`() {
    let result = WhatsNewState.resolve(
      current: version("1.3"), baselineString: "1.0",
      isOnboarded: true, catalog: [release(1, 1), release(1, 2)]
    )
    #expect(result == nil)
  }

  @Test
  func `matched entry with empty items suppresses`() {
    let result = WhatsNewState.resolve(
      current: version("1.1"), baselineString: nil,
      isOnboarded: true, catalog: [release(1, 1, items: 0)]
    )
    #expect(result == nil)
  }

  @Test
  func `re-launch after a show (baseline equals current) does not re-show`() {
    let result = WhatsNewState.resolve(
      current: version("1.1"), baselineString: "1.1",
      isOnboarded: true, catalog: [release(1, 1)]
    )
    #expect(result == nil)
  }

  // MARK: - evaluate side effects

  @Test
  func `screenshot mode neither shows nor writes a baseline`() {
    let state = WhatsNewState(defaults: defaults)

    state.evaluate(
      isOnboarded: true, isScreenshotMode: true,
      currentVersionString: "1.1", catalog: [release(1, 1)]
    )

    #expect(state.pendingRelease == nil)
    #expect(defaults.string(forKey: baselineKey) == nil)
  }

  @Test
  func `show path sets pendingRelease and defers the baseline to markShown`() {
    let state = WhatsNewState(defaults: defaults)

    state.evaluate(
      isOnboarded: true, isScreenshotMode: false,
      currentVersionString: "1.1", catalog: [release(1, 1)]
    )

    #expect(state.pendingRelease?.id == WhatsNewVersion(major: 1, minor: 1))
    #expect(defaults.string(forKey: baselineKey) == nil)
  }

  @Test
  func `suppress path finalizes the baseline immediately`() {
    let state = WhatsNewState(defaults: defaults)

    state.evaluate(
      isOnboarded: true, isScreenshotMode: false,
      currentVersionString: "1.1", catalog: []
    )

    #expect(state.pendingRelease == nil)
    #expect(defaults.string(forKey: baselineKey) == "1.1")
  }

  @Test
  func `unparseable version fails closed: neither shows nor writes a baseline`() {
    let state = WhatsNewState(defaults: defaults)

    state.evaluate(
      isOnboarded: true, isScreenshotMode: false,
      currentVersionString: "unknown", catalog: [release(1, 1)]
    )

    #expect(state.pendingRelease == nil)
    #expect(defaults.string(forKey: baselineKey) == nil)
  }

  @Test
  func `markShown persists the running version and clears the pending release`() {
    let state = WhatsNewState(defaults: defaults)
    state.pendingRelease = release(9, 9)

    state.markShown()

    #expect(state.pendingRelease == nil)
    #expect(defaults.string(forKey: baselineKey) == Bundle.main.appVersion)
  }
}
