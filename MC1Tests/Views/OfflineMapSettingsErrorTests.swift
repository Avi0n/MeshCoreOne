import MapLibre
@testable import MC1
import Testing
import UIKit

@Suite("Offline map settings errors", .serialized)
@MainActor
struct OfflineMapSettingsErrorTests {
  @Test
  func `opening settings presents a pending error across layout changes`() async throws {
    let harness = try SettingsTabHarness.make(connected: false, regularWidth: true)
    defer { harness.tearDown() }
    let maps = SettingsRoot.maps
    try #require(try await harness.open(title: maps.title, markers: maps.markers, detail: maps.detail))
    let message = "Offline map download failed before settings opened"
    let error = NSError(
      domain: "OfflineMapSettingsErrorTests",
      code: 1,
      userInfo: [NSLocalizedDescriptionKey: message]
    )
    NotificationCenter.default.post(
      name: .MLNOfflinePackError,
      object: nil,
      userInfo: [MLNOfflinePackUserInfoKey.error: error]
    )
    try #require(try await harness.waitUntil { harness.appState.offlineMapService.errorMessage == message })

    let destination = SettingsDestination.offlineMaps
    try #require(try await harness.openChild(
      row: destination.row, title: destination.title, markers: [message], detail: maps.detail
    ))
    try #require(try await harness.waitUntil { harness.controller.presentedViewController is UIAlertController })
    let alert = try #require(harness.controller.presentedViewController as? UIAlertController)
    #expect(alert.message == message)
    #expect(harness.labels(in: harness.window).contains(message))
    #expect(harness.appState.offlineMapService.errorMessage == message)

    for regularWidth in [false, true] {
      try #require(try await harness.resize(regularWidth: regularWidth))
      #expect(try await harness.waitUntil {
        harness.labels(in: harness.window).contains(message)
      }, "\(harness.describe())")
      #expect(harness.navigation.selectedSetting == .maps)
    }
    harness.appState.offlineMapService.clearError()
    #expect(try await harness.waitUntil { !harness.labels(in: harness.window).contains(message) })
  }
}
