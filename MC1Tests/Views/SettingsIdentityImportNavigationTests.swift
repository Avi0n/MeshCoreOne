@testable import MC1
@testable import MC1Services
import Testing

@Suite("Settings identity import navigation", .serialized)
@MainActor
struct SettingsIdentityImportNavigationTests {
  @Test(arguments: [false, true])
  func `identity rebuild keeps the import screen until the request disconnects`(regularWidth: Bool) async throws {
    let harness = try SettingsTabHarness.make(connected: true, regularWidth: regularWidth)
    defer { harness.tearDown() }
    let advanced = SettingsRoot.advanced
    try #require(
      try await harness.open(title: advanced.title, markers: advanced.markers, detail: advanced.detail),
      "\(harness.describe())"
    )
    try #require(
      try await harness.openRow(
        row: L10n.Settings.ConfigImport.importConfig,
        title: L10n.Settings.ConfigImport.title,
        markers: [L10n.Settings.ConfigImport.selectFile],
        detail: .advanced
      ),
      "\(harness.describe())"
    )

    harness.appState.connectionManager.setTestState(
      connectionState: .connecting,
      connectedDevice: .some(nil),
      identityRebuildUnpublished: true
    )
    harness.window.layoutIfNeeded()
    try #require(try await harness.waitUntil { showsImport(on: harness) }, "\(harness.describe())")
    try await Task.sleep(for: .milliseconds(300))
    #expect(showsImport(on: harness), "\(harness.describe())")

    harness.appState.connectionManager.setTestState(
      connectionState: .disconnected,
      connectedDevice: .some(nil),
      identityRebuildUnpublished: false
    )
    harness.window.layoutIfNeeded()
    try #require(
      try await harness.waitUntil {
        harness.navigation.selectedSetting == nil && harness.showsNavigationTitle(L10n.Settings.title)
      },
      "\(harness.describe())"
    )
    #expect(
      !harness.showsPage(
        title: L10n.Settings.ConfigImport.title,
        markers: [L10n.Settings.ConfigImport.selectFile]
      ),
      "\(harness.describe())"
    )
  }

  @Test
  func `a missing device without an identity rebuild closes the import screen`() async throws {
    let harness = try SettingsTabHarness.make(connected: true)
    defer { harness.tearDown() }
    let advanced = SettingsRoot.advanced
    try #require(
      try await harness.open(title: advanced.title, markers: advanced.markers, detail: advanced.detail),
      "\(harness.describe())"
    )
    try #require(
      try await harness.openRow(
        row: L10n.Settings.ConfigImport.importConfig,
        title: L10n.Settings.ConfigImport.title,
        markers: [L10n.Settings.ConfigImport.selectFile],
        detail: .advanced
      ),
      "\(harness.describe())"
    )

    harness.appState.connectionManager.setTestState(
      connectionState: .connecting,
      connectedDevice: .some(nil),
      identityRebuildUnpublished: false
    )
    harness.window.layoutIfNeeded()
    try #require(
      try await harness.waitUntil {
        harness.navigation.selectedSetting == nil && harness.showsNavigationTitle(L10n.Settings.title)
      },
      "\(harness.describe())"
    )
  }

  private func showsImport(on harness: SettingsTabHarness) -> Bool {
    harness.navigation.selectedSetting == .advanced
      && harness.appState.connectedDevice == nil
      && harness.appState.connectionManager.identityRebuildUnpublished
      && harness.showsPage(
        title: L10n.Settings.ConfigImport.title,
        markers: [L10n.Settings.ConfigImport.selectFile]
      )
  }
}
