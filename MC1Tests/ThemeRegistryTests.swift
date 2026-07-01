@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing

@Suite("ThemeRegistry")
struct ThemeRegistryTests {
  @Test
  func `allThemes holds the ten built-ins`() {
    #expect(ThemeRegistry.allThemes.count == 10)
    #expect(ThemeRegistry.allThemes.first?.id == "default")
  }

  @Test
  func `theme(forID:) resolves known IDs and returns nil for unknown`() {
    #expect(ThemeRegistry.theme(forID: "default")?.id == Theme.default.id)
    #expect(ThemeRegistry.theme(forID: "ember")?.id == "ember")
    #expect(ThemeRegistry.theme(forID: "does-not-exist") == nil)
  }

  @Test
  func `default theme has no productID; all paid themes do`() {
    #expect(Theme.default.productID == nil)
    let paid = ThemeRegistry.allThemes.filter { $0.id != "default" }
    #expect(paid.count == 9)
    #expect(paid.allSatisfy { $0.productID != nil })
  }

  @Test
  func `paid theme productIDs match StoreCatalog's bundled theme IDs`() {
    let registryProductIDs = Set(ThemeRegistry.allThemes.compactMap(\.productID))
    #expect(registryProductIDs == StoreCatalog.Theme.bundledThemeIDs)
    #expect(Theme.ember.productID == StoreCatalog.Theme.ember)
    #expect(Theme.solarized.productID == StoreCatalog.Theme.solarized)
  }

  @Test
  func `only Ember forces a color scheme, and it forces dark`() {
    #expect(Theme.ember.preferredColorScheme == .dark)
    let forced: Set = ["ember"]
    let others = ThemeRegistry.allThemes.filter { !forced.contains($0.id) }
    #expect(others.allSatisfy { $0.preferredColorScheme == nil })
  }

  @Test
  func `theme IDs are unique`() {
    let ids = ThemeRegistry.allThemes.map(\.id)
    #expect(Set(ids).count == ids.count)
  }
}
