@testable import MC1
import Testing
import UIKit

@Suite("WhatsNewCatalog")
struct WhatsNewCatalogTests {
  @Test
  func `every catalog SF Symbol name resolves to an image`() {
    for release in WhatsNewCatalog.releases {
      for item in release.items {
        #expect(
          UIImage(systemName: item.symbol) != nil,
          "SF Symbol \"\(item.symbol)\" does not resolve and would render blank"
        )
      }
    }
  }

  @Test
  func `no release ships an empty item list`() {
    for release in WhatsNewCatalog.releases {
      #expect(!release.items.isEmpty)
    }
  }
}
