@testable import MC1Services
import Testing

@Suite("StoreCatalog")
struct StoreCatalogTests {
  @Test
  func `sellableProductIDs are the bundle plus the six tips — themes are not sold standalone`() {
    #expect(StoreCatalog.sellableProductIDs.count == 7)
    #expect(StoreCatalog.sellableProductIDs.contains(StoreCatalog.Theme.bundleAll))
    #expect(StoreCatalog.sellableProductIDs.isSuperset(of: StoreCatalog.Tip.all))
    #expect(StoreCatalog.sellableProductIDs.isDisjoint(with: StoreCatalog.Theme.bundledThemeIDs))
  }

  @Test
  func `bundledThemeIDs are the nine themes the bundle unlocks, excluding the bundle itself`() {
    #expect(StoreCatalog.Theme.bundledThemeIDs.count == 9)
    #expect(!StoreCatalog.Theme.bundledThemeIDs.contains(StoreCatalog.Theme.bundleAll))
  }

  @Test
  func `Tip.all has six entries, disjoint from the themes`() {
    #expect(StoreCatalog.Tip.all.count == 6)
    #expect(StoreCatalog.Tip.all.isDisjoint(with: StoreCatalog.Theme.bundledThemeIDs))
    #expect(!StoreCatalog.Tip.all.contains(StoreCatalog.Theme.bundleAll))
  }

  @Test
  func `every product ID uses the io.pocketmesh.app prefix`() {
    for id in StoreCatalog.sellableProductIDs.union(StoreCatalog.Theme.bundledThemeIDs) {
      #expect(id.hasPrefix("io.pocketmesh.app."))
    }
  }
}

@Suite("Store value types")
struct StoreValueTypeTests {
  @Test
  func `StoreLoadState is Equatable across its cases`() {
    #expect(StoreLoadState.idle == .idle)
    #expect(StoreLoadState.loading != .loaded)
    #expect(StoreLoadState.failed != .idle)
  }

  @Test
  func `StorePurchaseOutcome distinguishes its three cases`() {
    #expect(StorePurchaseOutcome.purchased == .purchased)
    #expect(StorePurchaseOutcome.pending != .purchased)
    #expect(StorePurchaseOutcome.userCancelled != .pending)
  }
}
