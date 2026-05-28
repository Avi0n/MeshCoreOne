import Testing
@testable import MC1Services

@Suite("StoreCatalog")
struct StoreCatalogTests {

    @Test("allProductIDs contains every theme, the bundle, and every tip")
    func allProductIDsCount() {
        #expect(StoreCatalog.allProductIDs.count == 14)
    }

    @Test("individualIDs are the seven non-bundle themes")
    func individualThemeCount() {
        #expect(StoreCatalog.Theme.individualIDs.count == 7)
        #expect(!StoreCatalog.Theme.individualIDs.contains(StoreCatalog.Theme.bundleAll))
    }

    @Test("Theme.all includes the bundle; Tip.all has six entries")
    func aggregateCounts() {
        #expect(StoreCatalog.Theme.all.count == 8)
        #expect(StoreCatalog.Theme.all.contains(StoreCatalog.Theme.bundleAll))
        #expect(StoreCatalog.Tip.all.count == 6)
    }

    @Test("theme and tip namespaces are disjoint")
    func namespacesDisjoint() {
        #expect(StoreCatalog.Theme.all.isDisjoint(with: StoreCatalog.Tip.all))
    }

    @Test("every product ID uses the io.pocketmesh.app prefix")
    func productIDPrefix() {
        for id in StoreCatalog.allProductIDs {
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
