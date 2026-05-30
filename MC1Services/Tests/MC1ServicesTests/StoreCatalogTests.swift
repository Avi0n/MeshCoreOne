import Testing
@testable import MC1Services

@Suite("StoreCatalog")
struct StoreCatalogTests {

    @Test("sellableProductIDs are the bundle plus the six tips — themes are not sold standalone")
    func sellableProductIDsCount() {
        #expect(StoreCatalog.sellableProductIDs.count == 7)
        #expect(StoreCatalog.sellableProductIDs.contains(StoreCatalog.Theme.bundleAll))
        #expect(StoreCatalog.sellableProductIDs.isSuperset(of: StoreCatalog.Tip.all))
        #expect(StoreCatalog.sellableProductIDs.isDisjoint(with: StoreCatalog.Theme.bundledThemeIDs))
    }

    @Test("bundledThemeIDs are the nine themes the bundle unlocks, excluding the bundle itself")
    func bundledThemeCount() {
        #expect(StoreCatalog.Theme.bundledThemeIDs.count == 9)
        #expect(!StoreCatalog.Theme.bundledThemeIDs.contains(StoreCatalog.Theme.bundleAll))
    }

    @Test("Tip.all has six entries, disjoint from the themes")
    func tipCounts() {
        #expect(StoreCatalog.Tip.all.count == 6)
        #expect(StoreCatalog.Tip.all.isDisjoint(with: StoreCatalog.Theme.bundledThemeIDs))
        #expect(!StoreCatalog.Tip.all.contains(StoreCatalog.Theme.bundleAll))
    }

    @Test("every product ID uses the io.pocketmesh.app prefix")
    func productIDPrefix() {
        for id in StoreCatalog.sellableProductIDs.union(StoreCatalog.Theme.bundledThemeIDs) {
            #expect(id.hasPrefix("io.pocketmesh.app."))
        }
    }
}

@Suite("Store value types")
struct StoreValueTypeTests {

    @Test("StoreLoadState is Equatable across its cases")
    func loadStateEquatable() {
        #expect(StoreLoadState.idle == .idle)
        #expect(StoreLoadState.loading != .loaded)
        #expect(StoreLoadState.failed != .idle)
    }

    @Test("StorePurchaseOutcome distinguishes its three cases")
    func purchaseOutcomeEquatable() {
        #expect(StorePurchaseOutcome.purchased == .purchased)
        #expect(StorePurchaseOutcome.pending != .purchased)
        #expect(StorePurchaseOutcome.userCancelled != .pending)
    }
}
