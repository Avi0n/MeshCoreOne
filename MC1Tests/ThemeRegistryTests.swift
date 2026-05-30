import Testing
import SwiftUI
import MC1Services
@testable import MC1

@Suite("ThemeRegistry")
struct ThemeRegistryTests {

    @Test("allThemes holds the ten built-ins")
    func allThemesCount() {
        #expect(ThemeRegistry.allThemes.count == 10)
        #expect(ThemeRegistry.allThemes.first?.id == "default")
    }

    @Test("theme(forID:) resolves known IDs and returns nil for unknown")
    func themeLookup() {
        #expect(ThemeRegistry.theme(forID: "default")?.id == Theme.default.id)
        #expect(ThemeRegistry.theme(forID: "ember")?.id == "ember")
        #expect(ThemeRegistry.theme(forID: "does-not-exist") == nil)
    }

    @Test("default theme has no productID; all paid themes do")
    func productIDPresence() {
        #expect(Theme.default.productID == nil)
        let paid = ThemeRegistry.allThemes.filter { $0.id != "default" }
        #expect(paid.count == 9)
        #expect(paid.allSatisfy { $0.productID != nil })
    }

    @Test("paid theme productIDs match StoreCatalog's bundled theme IDs")
    func productIDsMatchCatalog() {
        let registryProductIDs = Set(ThemeRegistry.allThemes.compactMap { $0.productID })
        #expect(registryProductIDs == StoreCatalog.Theme.bundledThemeIDs)
        #expect(Theme.ember.productID == StoreCatalog.Theme.ember)
        #expect(Theme.solarized.productID == StoreCatalog.Theme.solarized)
    }

    @Test("only Ember forces a color scheme, and it forces dark")
    func forcedColorSchemeThemes() {
        #expect(Theme.ember.preferredColorScheme == .dark)
        let forced: Set<String> = ["ember"]
        let others = ThemeRegistry.allThemes.filter { !forced.contains($0.id) }
        #expect(others.allSatisfy { $0.preferredColorScheme == nil })
    }

    @Test("theme IDs are unique")
    func idsAreUnique() {
        let ids = ThemeRegistry.allThemes.map(\.id)
        #expect(Set(ids).count == ids.count)
    }
}
