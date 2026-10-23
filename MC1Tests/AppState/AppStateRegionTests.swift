import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("AppState region preference", .serialized)
@MainActor
struct AppStateRegionTests {
  private static let regionKey = BackupUserDefaults.regionSelectionKey
  private let preferences = IsolatedUserDefaults()

  private var defaults: UserDefaults {
    preferences.defaults
  }

  @Test
  func `regionSelection persists to UserDefaults on set`() throws {
    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                 countyKey: "los angeles", source: .location)
    appState.regionSelection = region

    let data = try #require(defaults.data(forKey: Self.regionKey))
    let decoded = try JSONDecoder().decode(RegionSelection.self, from: data)
    #expect(decoded == region)
  }

  @Test
  func `regionSelection clears UserDefaults on nil`() {
    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }
    appState.regionSelection = RegionSelection(countryCode: "US", source: .manual)
    appState.regionSelection = nil
    #expect(defaults.data(forKey: Self.regionKey) == nil)
  }

  @Test
  func `AppState loads persisted regionSelection on init`() throws {
    let region = RegionSelection(countryCode: "PT", source: .manual)
    let data = try JSONEncoder().encode(region)
    defaults.set(data, forKey: Self.regionKey)

    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }
    #expect(appState.regionSelection == region)
  }

  @Test
  func `separate AppState preferences keep regions isolated`() {
    let otherPreferences = IsolatedUserDefaults()
    let region = RegionSelection(countryCode: "US", source: .manual)
    let otherRegion = RegionSelection(countryCode: "PT", source: .manual)
    BackupUserDefaults.persistRegionSelection(region, to: defaults)
    BackupUserDefaults.persistRegionSelection(otherRegion, to: otherPreferences.defaults)
    let appState = AppState(defaults: defaults)
    let otherAppState = AppState(defaults: otherPreferences.defaults)
    defer {
      appState.shutdown()
      otherAppState.shutdown()
    }

    #expect(appState.regionSelection == region)
    #expect(otherAppState.regionSelection == otherRegion)
    appState.regionSelection = nil
    #expect(defaults.data(forKey: Self.regionKey) == nil)
    #expect(otherAppState.regionSelection == otherRegion)
    #expect(BackupUserDefaults.loadRegionSelection(from: otherPreferences.defaults) == otherRegion)
  }
}
