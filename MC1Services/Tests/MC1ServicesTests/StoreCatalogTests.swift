import Testing
@testable import MC1Services

@Suite("StoreCatalog")
struct StoreCatalogTests {

    @Test("allProductIDs contains every theme, the bundle, and every tip")
    func allProductIDsCount() {
        #expect(StoreCatalog.allProductIDs.count == 16)
    }

    @Test("individualIDs are the six named application themes")
    func individualThemeCount() {
        #expect(StoreCatalog.Theme.individualIDs.count == 6)
        #expect(!StoreCatalog.Theme.individualIDs.contains(StoreCatalog.Theme.bundleAll))
    }

    @Test("referenceIDs are the three reference-palette themes, excluded from the named set")
    func referenceThemeCount() {
        #expect(StoreCatalog.Theme.referenceIDs.count == 3)
        #expect(StoreCatalog.Theme.referenceIDs.isDisjoint(with: StoreCatalog.Theme.individualIDs))
        #expect(!StoreCatalog.Theme.referenceIDs.contains(StoreCatalog.Theme.bundleAll))
        #expect(StoreCatalog.Theme.purchasableIndividually.count == 9)
    }

    @Test("Theme.all includes the bundle and reference themes; Tip.all has six entries")
    func aggregateCounts() {
        #expect(StoreCatalog.Theme.all.count == 10)
        #expect(StoreCatalog.Theme.all.contains(StoreCatalog.Theme.bundleAll))
        #expect(StoreCatalog.Theme.all.isSuperset(of: StoreCatalog.Theme.referenceIDs))
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
