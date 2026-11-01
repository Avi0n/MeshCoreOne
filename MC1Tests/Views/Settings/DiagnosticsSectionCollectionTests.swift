import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import SwiftUI
import Testing
import UIKit

/// Serialized because each test mutates the process `collectDebugLogs` key.
/// `@AppStorage` reads `UserDefaults.standard` here, so setup saves that value and teardown restores it.
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

  /// `connectionManager.persistenceStore` is the store `disableCollection(using:)` clears.
  private func makeLiveAppState() throws -> AppState {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return AppState(modelContainer: container, isPlaceholder: true)
  }

  /// Hosts `DiagnosticsSection` so rows, footers, and the disable alert lay out. No `.defaultAppStorage`, so `@AppStorage` uses standard defaults.
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

  /// Headers render uppercase, and rows concatenate label and value, so the match is exact or a prefix.
  private func matchesCore(_ text: String, target: String) -> Bool {
    let text = text.folding(options: .caseInsensitive, locale: .current)
    let target = target.folding(options: .caseInsensitive, locale: .current)
    if text == target { return true }
    return text.hasPrefix(target + ",") || text.hasPrefix(target + "\n") || text.hasPrefix(target + " ")
  }

  /// Labels from the accessibility tree. `List` rows and alert actions have no `UILabel`; this matches `SettingsTabHarness`.
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

  private func allLabels() -> [String] {
    sceneWindows().flatMap { accessibilityLabels(in: $0) }
  }

  private func labelMatches(_ target: String) -> Bool {
    allLabels().contains { matchesLabel($0, target: target) }
  }

  /// The section's only `UISwitch`, which is the collection `Toggle`.
  private func collectionSwitch(in window: UIWindow) -> UISwitch? {
    descendants(of: window, UISwitch.self).first
  }

  /// Drives the collection `UISwitch` with `setOn` and `valueChanged`, the path a SwiftUI `Toggle` observes.
  private func driveSwitch(to targetValue: Bool, in window: UIWindow) -> Bool {
    window.layoutIfNeeded()
    guard let toggle = collectionSwitch(in: window), toggle.isOn != targetValue else { return false }
    toggle.setOn(targetValue, animated: false)
    toggle.sendActions(for: .valueChanged)
    return true
  }

  /// Polls `condition` until the timeout. `pump` lays out each window so a pending render can settle.
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

  /// Lays out every scene window so the next `condition` read sees the latest state.
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

  /// The switch raises the alert and does not write the key. Confirm calls `disableCollection(using:)` directly; the alert button has no in-process driver.
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

    #expect(driveSwitch(to: false, in: root))

    let alertReady = await waitFor { self.labelMatches(Self.disableConfirmText) }
    #expect(alertReady)
    #expect(UserDefaults.standard.bool(forKey: Self.key))

    let section = DiagnosticsSection(exportedFile: .constant(nil), isSidebar: false)
    await section.disableCollection(using: store)

    #expect(UserDefaults.standard.bool(forKey: Self.key) == false)
    let remaining = try await store.countDebugLogEntries()
    #expect(remaining == 0)
  }

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

/// Throws from `clearDebugLogEntries` so `disableCollection(using:)` takes its restore path.
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

/// Parks `clearDebugLogEntries` until `releaseClear`, so a turn-on can run while confirm is in flight.
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
