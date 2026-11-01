import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import SwiftUI
import Testing
import UIKit

/// App-hosted tests for the "Collect Debug Logs" toggle in `DiagnosticsSection`.
///
/// The view's `@AppStorage(AppStorageKey.collectDebugLogs…)` reads the process's
/// standard `UserDefaults` domain (these hosts inject no `.defaultAppStorage`
/// override), so each test captures the current value, drives the key through
/// `UserDefaults.standard` itself, and restores it in teardown — matching how the
/// MC1Services gate tests treat the same domain.
///
/// Serialized because every test mutates that shared process-global key.
@MainActor
@Suite("DiagnosticsSection collection toggle", .serialized)
struct DiagnosticsSectionCollectionTests {
  private static let key = AppStorageKey.collectDebugLogs.rawValue
  private static let enableText = L10n.Settings.Diagnostics.collectLogs
  private static let exportText = L10n.Settings.Diagnostics.exportLogs
  private static let clearText = L10n.Settings.Diagnostics.clearLogs
  private static let footerOnText = L10n.Settings.Diagnostics.footer
  private static let footerOffText = L10n.Settings.Diagnostics.Footer.disabled
  private static let disableConfirmText = L10n.Settings.Diagnostics.Alert.Disable.confirm

  private enum Timing {
    static let timeout: Duration = .seconds(6)
    static let pollInterval: Duration = .milliseconds(20)
  }

  // MARK: - Hosting

  /// A live `AppState` on its own in-memory store. `connectionManager.persistenceStore`
  /// is process-lifetime, so it is the exact object `DiagnosticsSection` reaches for
  /// through `disableCollection`.
  private func makeLiveAppState() throws -> AppState {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return AppState(modelContainer: container, isPlaceholder: true)
  }

  /// Hosts `DiagnosticsSection` in a `NavigationStack` so section rows and footers
  /// lay out and the disable alert can present over the hosting window. No
  /// `.defaultAppStorage` is applied, so `@AppStorage` resolves to standard defaults.
  private func host(_ appState: AppState) throws -> (window: UIWindow, controller: UIHostingController<AnyView>) {
    let root = NavigationStack {
      FeedbackView()
    }
    .environment(\.appState, appState)
    .environment(\.appTheme, Theme.default)
    .environment(\.scenePhase, .active)

    let controller = UIHostingController(rootView: AnyView(root))
    controller.view.frame = CGRect(origin: .zero, size: CGSize(width: 390, height: 844))

    let scene = try #require(
      UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first,
      "no connected UIWindowScene; the app test host requires one"
    )
    let window = UIWindow(windowScene: scene)
    window.frame = CGRect(origin: .zero, size: CGSize(width: 390, height: 844))
    window.rootViewController = controller
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return (window, controller)
  }

  private func tearDown(window: inout UIWindow?, controller: UIHostingController<AnyView>?, appState: AppState?) {
    controller?.view.endEditing(true)
    window?.rootViewController = nil
    window?.isHidden = true
    window?.windowScene = nil
    window = nil
    appState?.shutdown()
  }

  // MARK: - View-tree traversal

  private func descendants<T: UIView>(of view: UIView, _ type: T.Type) -> [T] {
    ((view as? T).map { [$0] } ?? []) + view.subviews.flatMap { descendants(of: $0, type) }
  }

  private func sceneWindows() -> [UIWindow] {
    UIApplication.shared.connectedScenes.flatMap { ($0 as? UIWindowScene)?.windows ?? [] }
  }

  private func matchesLabel(_ candidate: String?, target: String) -> Bool {
    guard let candidate else { return false }
    let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
    if matchesCore(trimmed, target: target) { return true }
    let stripped = trimmed.drop { !$0.isLetter && !$0.isNumber }
    return matchesCore(String(stripped), target: target)
  }

  /// Section headers render uppercase without changing their localized text, and rows
  /// concatenate label + value, so an exact / prefix match is the contract.
  private func matchesCore(_ text: String, target: String) -> Bool {
    let text = text.folding(options: .caseInsensitive, locale: .current)
    let target = target.folding(options: .caseInsensitive, locale: .current)
    if text == target { return true }
    return text.hasPrefix(target + ",") || text.hasPrefix(target + "\n") || text.hasPrefix(target + " ")
  }

  /// The labels SwiftUI exposes across the accessibility-element tree — this is how
  /// `List` rows and alert actions surface text, since SwiftUI draws `Text` as CoreText
  /// layers with no backing `UILabel`. Mirrors `SettingsTabHarness.activateAccessibility`.
  private func accessibilityLabels(in view: UIView) -> [String] {
    var found: [String] = []
    func walk(_ object: NSObject) {
      if object !== view, let label = object.accessibilityLabel { found.append(label) }
      guard let view = object as? UIView else { return }
      let count = view.accessibilityElementCount()
      if count != NSNotFound, count > 0 {
        for index in 0..<count {
          guard let element = view.accessibilityElement(at: index) as? NSObject, element !== view else { continue }
          walk(element)
        }
      }
      for subview in view.subviews {
        walk(subview)
      }
    }
    walk(view)
    return found
  }

  /// Every accessibility label rendered in any scene window.
  private func allLabels() -> [String] {
    sceneWindows().flatMap { accessibilityLabels(in: $0) }
  }

  private func labelMatches(_ target: String) -> Bool {
    allLabels().contains { matchesLabel($0, target: target) }
  }

  /// The `UISwitch` SwiftUI's `Toggle` renders: the switch on the same cell as the
  /// toggle's accessibility identifier.
  private func collectionSwitch(in window: UIWindow) -> UISwitch? {
    descendants(of: window, UISwitch.self).first
  }

  /// Drives the collection switch to `targetValue` through the live view tree, the
  /// same `setOn` + `valueChanged` path the chat pin-switch tests use for a SwiftUI
  /// `Toggle`.
  private func driveSwitch(to targetValue: Bool, in window: UIWindow) -> Bool {
    window.layoutIfNeeded()
    guard let toggle = collectionSwitch(in: window), toggle.isOn != targetValue else { return false }
    toggle.setOn(targetValue, animated: false)
    toggle.sendActions(for: .valueChanged)
    return true
  }

  /// Polls a synchronous UI `condition`, yielding with `Task.sleep` so a pending
  /// SwiftUI re-render or alert presentation can settle. Between reads, `layoutIfNeeded`
  /// on each connected window forces a synchronous layout pass.
  private func waitFor(_ condition: @MainActor () -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + Timing.timeout
    while ContinuousClock.now < deadline {
      if condition() { return true }
      try? await Task.sleep(for: Timing.pollInterval)
      pump()
    }
    pump()
    return condition()
  }

  /// Forces a synchronous layout on every scene window so a just-committed state
  /// change is reflected in the view tree on the next `condition()` read.
  private func pump() {
    for window in sceneWindows() {
      window.layoutIfNeeded()
    }
  }

  // MARK: - Key capture / restore

  private func captureKey() -> Any? {
    UserDefaults.standard.object(forKey: Self.key)
  }

  private func restoreKey(_ captured: Any?) {
    if let captured {
      UserDefaults.standard.set(captured, forKey: Self.key)
    } else {
      UserDefaults.standard.removeObject(forKey: Self.key)
    }
  }

  // MARK: - Tests

  /// Key true: the toggle row is present and reads on; both action buttons appear;
  /// the footer is today's on-path string.
  @Test
  func `collection on shows toggle, both buttons, and the on footer`() async throws {
    let captured = captureKey()
    UserDefaults.standard.set(true, forKey: Self.key)
    defer { restoreKey(captured) }

    let appState = try makeLiveAppState()
    var window: UIWindow?
    var controller: UIHostingController<AnyView>?
    do {
      let hostResult = try host(appState)
      window = hostResult.window
      controller = hostResult.controller
    }
    defer { tearDown(window: &window, controller: controller, appState: appState) }
    let root = try #require(window)

    let ready = await waitFor { self.labelMatches(Self.enableText) }
    #expect(ready)

    #expect(self.labelMatches(Self.exportText))
    #expect(self.labelMatches(Self.clearText))
    #expect(self.labelMatches(Self.footerOnText))
    #expect(self.collectionSwitch(in: root)?.isOn == true)
  }

  /// Key false: both action buttons are absent and the disabled footer replaces the
  /// on-path footer.
  @Test
  func `collection off hides both buttons and shows the disabled footer`() async throws {
    let captured = captureKey()
    UserDefaults.standard.set(false, forKey: Self.key)
    defer { restoreKey(captured) }

    let appState = try makeLiveAppState()
    var window: UIWindow?
    var controller: UIHostingController<AnyView>?
    do {
      let hostResult = try host(appState)
      window = hostResult.window
      controller = hostResult.controller
    }
    defer { tearDown(window: &window, controller: controller, appState: appState) }
    let root = try #require(window)

    let ready = await waitFor { self.labelMatches(Self.footerOffText) }
    #expect(ready)

    #expect(!self.labelMatches(Self.exportText))
    #expect(!self.labelMatches(Self.clearText))
    #expect(!self.labelMatches(Self.footerOnText))
    #expect(self.collectionSwitch(in: root)?.isOn == false)
  }

  /// Off-transition through the view: seed a persisted row, flip the switch off (which
  /// raises the confirm alert instead of writing), then run the confirm action against
  /// the live store. The alert appears; toggling the switch off does not write the key
  /// or delete rows. The confirm action is `disableCollection(using:)`, called here
  /// because SwiftUI's alert button exposes no in-process driver. The test does not
  /// write the key; a successful confirm leaves it false.
  @Test
  func `toggling off raises the alert and confirm clears rows and sets the key false`() async throws {
    let captured = captureKey()
    UserDefaults.standard.set(true, forKey: Self.key)
    defer { restoreKey(captured) }

    let appState = try makeLiveAppState()
    let store = appState.connectionManager.persistenceStore
    try await store.saveDebugLogEntries([
      DebugLogEntryDTO(level: .info, subsystem: "test", category: "ui", message: "seed")
    ])
    #expect(try await store.countDebugLogEntries() == 1)

    var window: UIWindow?
    var controller: UIHostingController<AnyView>?
    do {
      let hostResult = try host(appState)
      window = hostResult.window
      controller = hostResult.controller
    }
    defer { tearDown(window: &window, controller: controller, appState: appState) }
    let root = try #require(window)

    let ready = await waitFor { self.collectionSwitch(in: root)?.isOn == true }
    #expect(ready)

    // Flip the switch off; the setter raises the confirm alert instead of writing.
    #expect(driveSwitch(to: false, in: root))

    let alertReady = await waitFor { self.labelMatches(Self.disableConfirmText) }
    #expect(alertReady)
    // The deferred-write contract: raising the alert writes nothing yet.
    #expect(UserDefaults.standard.bool(forKey: Self.key))

    let section = DiagnosticsSection(exportedFile: .constant(nil), isSidebar: false)
    await section.disableCollection(using: store)

    #expect(UserDefaults.standard.bool(forKey: Self.key) == false)
    let remaining = try await store.countDebugLogEntries()
    #expect(remaining == 0)
  }

  /// Confirm against a store whose clear throws. The service leaves the key false
  /// and the rows in place; the confirm action turns the key back on and sets
  /// `errorMessage`.
  @Test
  func `failed confirm restores the key, keeps the rows, and sets errorMessage`() async throws {
    let captured = captureKey()
    UserDefaults.standard.set(true, forKey: Self.key)
    defer { restoreKey(captured) }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let inner = PersistenceStore(modelContainer: container)
    try await inner.saveDebugLogEntries([
      DebugLogEntryDTO(level: .info, subsystem: "test", category: "ui", message: "seed")
    ])
    let failing = FailingClearStore(inner: inner)
    let section = DiagnosticsSection(exportedFile: .constant(nil), isSidebar: false)

    await section.disableCollection(using: failing)

    #expect(UserDefaults.standard.bool(forKey: Self.key))
    #expect(try await inner.countDebugLogEntries() == 1)
    let message = section.errorMessage
    #expect(
      message == ClearFailure.message,
      "errorMessage was \(message ?? "nil")"
    )
  }

  /// Clear parks, confirm is in flight, and the on-write does not stick. After
  /// confirm returns, a later on-write sets the key true.
  @Test
  func `on write during confirm does not stick and a later on write enables collection`() async {
    let captured = captureKey()
    UserDefaults.standard.set(true, forKey: Self.key)
    defer { restoreKey(captured) }

    let store = SuspendingClearStore()
    let section = DiagnosticsSection(exportedFile: .constant(nil), isSidebar: false)
    let confirm = Task { await section.disableCollection(using: store) }

    let started = await waitForClearToStart(store)
    #expect(started)

    section.setCollectionEnabled(true)
    #expect(UserDefaults.standard.bool(forKey: Self.key) == false)

    await store.releaseClear()
    await confirm.value

    #expect(UserDefaults.standard.bool(forKey: Self.key) == false)
    section.setCollectionEnabled(true)
    #expect(UserDefaults.standard.bool(forKey: Self.key))
  }

  private func waitForClearToStart(_ store: SuspendingClearStore) async -> Bool {
    let deadline = ContinuousClock.now + Timing.timeout
    while ContinuousClock.now < deadline {
      if await store.clearDidStart { return true }
      try? await Task.sleep(for: Timing.pollInterval)
    }
    return await store.clearDidStart
  }
}

/// Wraps an in-memory `PersistenceStore` and throws from `clearDebugLogEntries`,
/// mirroring the app view's `any DebugLogPersisting` seam used by `disableCollection`.
private struct ClearFailure: LocalizedError {
  static let message = "The debug log could not be cleared."
  var errorDescription: String? {
    Self.message
  }
}

private actor FailingClearStore: DebugLogPersisting {
  private let inner: PersistenceStore

  init(inner: PersistenceStore) {
    self.inner = inner
  }

  func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws {
    try await inner.saveDebugLogEntries(dtos)
  }

  func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO] {
    try await inner.fetchDebugLogEntries(since: date, limit: limit)
  }

  func countDebugLogEntries() async throws -> Int {
    try await inner.countDebugLogEntries()
  }

  func pruneDebugLogEntries(olderThan cutoff: Date, keepCount: Int) async throws {
    try await inner.pruneDebugLogEntries(olderThan: cutoff, keepCount: keepCount)
  }

  func clearDebugLogEntries() async throws {
    throw ClearFailure()
  }
}

/// Parks `clearDebugLogEntries` until `releaseClear`, so a test can turn the
/// toggle on while confirm is in flight.
private actor SuspendingClearStore: DebugLogPersisting {
  private var waiter: CheckedContinuation<Void, Never>?
  private(set) var clearDidStart = false

  func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws {}

  func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO] {
    []
  }

  func countDebugLogEntries() async throws -> Int {
    0
  }

  func pruneDebugLogEntries(olderThan cutoff: Date, keepCount: Int) async throws {}

  func clearDebugLogEntries() async throws {
    clearDidStart = true
    await withCheckedContinuation { continuation in
      waiter = continuation
    }
  }

  func releaseClear() {
    waiter?.resume()
    waiter = nil
  }
}
