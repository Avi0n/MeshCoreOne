import Testing
import UIKit
@testable import MC1

@Suite("WhatsNewCatalog")
struct WhatsNewCatalogTests {

    @Test("every catalog SF Symbol name resolves to an image")
    func everySymbolResolves() {
        for release in WhatsNewCatalog.releases {
            for item in release.items {
                #expect(
                    UIImage(systemName: item.symbol) != nil,
                    "SF Symbol \"\(item.symbol)\" does not resolve and would render blank"
                )
            }
        }
    }

    @Test("no release ships an empty item list")
    func noEmptyReleases() {
        for release in WhatsNewCatalog.releases {
            #expect(!release.items.isEmpty)
        }
    }
}
