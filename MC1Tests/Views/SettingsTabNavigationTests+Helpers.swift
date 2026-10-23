import Foundation
@testable import MC1
import MC1Services
import Testing

extension SettingsTabNavigationTests {
  func mounted(_ root: SettingsRoot) throws -> SettingsTabHarness {
    try SettingsTabHarness.make(connected: root.requiresDevice)
  }

  func requireRoot(_ root: SettingsRoot, on harness: SettingsTabHarness) async throws {
    try #require(try await harness.open(title: root.title, markers: root.markers, detail: root.detail), "\(harness.describe())")
  }

  func requireChild(_ destination: SettingsDestination, on harness: SettingsTabHarness) async throws {
    try await requireRoot(destination.root, on: harness)
    try #require(
      try await harness.openChild(row: destination.row, title: destination.title, markers: destination.markers, detail: destination.root.detail),
      "\(harness.describe())"
    )
  }

  func captureSearch(_ destination: SettingsDestination, on harness: SettingsTabHarness) async throws {
    guard destination.preservesSearch else { return }
    try #require(try await harness.waitUntil { harness.searchQuery() != nil }, "\(harness.describe())")
    try #require(harness.activateSearch(), "\(harness.describe())")
    try #require(try await harness.waitUntil { harness.searchPresentationIsSettled }, "\(harness.describe())")
    try #require(harness.setSearchQuery(SettingsTabHarness.searchQuery), "\(harness.describe())")
    try #require(try await harness.waitUntil {
      harness.searchPresentationIsSettled
        && harness.searchQuery() == SettingsTabHarness.searchQuery
        && showsChild(destination, on: harness, searching: true)
        && !harness.showsPage(title: destination.title, markers: destination.markers)
    }, "\(harness.describe())")
  }

  func requireSearchPreserved(_ destination: SettingsDestination, on harness: SettingsTabHarness) async throws {
    guard destination.preservesSearch else { return }
    #expect(try await harness.waitUntil { harness.searchQuery() == SettingsTabHarness.searchQuery }, "\(harness.describe())")
  }

  func leaveAndReturn(_ harness: SettingsTabHarness) async throws {
    harness.selectTab(.chats)
    try #require(try await harness.waitUntil { harness.showsSelectedTab(.chats) }, "\(harness.describe())")
    harness.selectTab(.settings)
    try #require(try await harness.waitUntil { harness.showsSelectedTab(.settings) }, "\(harness.describe())")
  }

  func showsRoot(_ root: SettingsRoot, on harness: SettingsTabHarness) -> Bool {
    harness.navigation.selectedSetting == root.detail && harness.showsPage(title: root.title, markers: root.markers)
  }

  func showsChild(_ destination: SettingsDestination, on harness: SettingsTabHarness, searching: Bool) -> Bool {
    let markers = searching
      ? [TranslationTargetPreference.displayName(
        forLanguageCode: SettingsTabHarness.translationLanguageCode,
        in: .current
      ).autonym]
      : destination.markers
    return harness.navigation.selectedSetting == destination.root.detail
      && harness.showsPage(title: destination.title, markers: markers)
  }

  func childTitleIsHidden(_ destination: SettingsDestination, on harness: SettingsTabHarness) -> Bool {
    !harness.showsNavigationTitle(destination.title)
  }

  func showsBackup(on harness: SettingsTabHarness) -> Bool {
    harness.navigation.selectedSetting == .backup
      && harness.showsPage(
        title: L10n.Settings.Settings.Backup.title,
        markers: [L10n.Settings.Settings.Backup.FileBackup.header]
      )
  }
}
