import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@Suite("AppState region preference", .serialized)
@MainActor
struct AppStateRegionTests {

    private static let regionKey = BackupUserDefaults.regionSelectionKey

    @Test("regionSelection persists to UserDefaults on set")
    func persistsOnSet() throws {
        UserDefaults.standard.removeObject(forKey: Self.regionKey)
        let appState = AppState()
        let region = RegionSelection(countryCode: "US", administrativeAreaCode: "US-CA",
                                     countyKey: "los angeles", source: .location)
        appState.regionSelection = region

        let data = try #require(UserDefaults.standard.data(forKey: Self.regionKey))
        let decoded = try JSONDecoder().decode(RegionSelection.self, from: data)
        #expect(decoded == region)
    }

    @Test("regionSelection clears UserDefaults on nil")
    func clearsOnNil() throws {
        UserDefaults.standard.removeObject(forKey: Self.regionKey)
        let appState = AppState()
        appState.regionSelection = RegionSelection(countryCode: "US", source: .manual)
        appState.regionSelection = nil
        #expect(UserDefaults.standard.data(forKey: Self.regionKey) == nil)
    }

    @Test("AppState loads persisted regionSelection on init")
    func loadsOnInit() throws {
        defer { UserDefaults.standard.removeObject(forKey: Self.regionKey) }
        let region = RegionSelection(countryCode: "PT", source: .manual)
        let data = try JSONEncoder().encode(region)
        UserDefaults.standard.set(data, forKey: Self.regionKey)

        let appState = AppState()
        #expect(appState.regionSelection == region)
    }
}
