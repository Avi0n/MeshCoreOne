import Foundation
@testable import MC1Services
import Testing

@Suite("Remote flood fallback")
struct RemoteFloodFallbackTests {
  @Test
  func `a missing key is on`() throws {
    let suiteName = "RemoteFloodFallback.missing.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }

    #expect(RemoteFloodFallback.isEnabled(defaults: defaults))
  }

  @Test
  func `stored false is off and stored true is on`() throws {
    let suiteName = "RemoteFloodFallback.stored.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let key = AppStorageKey.remoteFloodFallback.rawValue

    defaults.set(false, forKey: key)
    #expect(RemoteFloodFallback.isEnabled(defaults: defaults) == false)

    defaults.set(true, forKey: key)
    #expect(RemoteFloodFallback.isEnabled(defaults: defaults))
  }
}
