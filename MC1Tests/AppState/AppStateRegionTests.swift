import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("AppState region preference", .serialized)
@MainActor
struct AppStateRegionTests {
  private static let regionKey = BackupUserDefaults.regionSelectionKey

  @Test
  func `regionSelection persists to UserDefaults on set`() throws {
    UserDefaults.standard.removeObject(forKey: Self.regionKey)
    let appState = AppState()
    let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                 countyKey: "los angeles", source: .location)
    appState.regionSelection = region

    let data = try #require(UserDefaults.standard.data(forKey: Self.regionKey))
    let decoded = try JSONDecoder().decode(RegionSelection.self, from: data)
    #expect(decoded == region)
  }

  @Test
  func `regionSelection clears UserDefaults on nil`() {
    UserDefaults.standard.removeObject(forKey: Self.regionKey)
    let appState = AppState()
    appState.regionSelection = RegionSelection(countryCode: "US", source: .manual)
    appState.regionSelection = nil
    #expect(UserDefaults.standard.data(forKey: Self.regionKey) == nil)
  }

  @Test
  func `AppState loads persisted regionSelection on init`() throws {
    defer { UserDefaults.standard.removeObject(forKey: Self.regionKey) }
    let region = RegionSelection(countryCode: "PT", source: .manual)
    let data = try JSONEncoder().encode(region)
    UserDefaults.standard.set(data, forKey: Self.regionKey)

    let appState = AppState()
    #expect(appState.regionSelection == region)
  }
}
