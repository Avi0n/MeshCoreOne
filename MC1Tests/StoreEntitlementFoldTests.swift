import Testing
@testable import MC1Services

/// Exercises `StoreService.applyEntitlement(productID:isRevoked:)` directly — the additive fold
/// that grants a purchase from its returned transaction instead of re-reading
/// `Transaction.currentEntitlements`. Pure logic with no StoreKit dependency, so unlike the
/// `SKTestSession`-backed `StoreServiceTests` it runs on every simulator runtime (including the
/// iOS 26.x sims where storekitd serves no products under `xcodebuild test`).
@MainActor
@Suite("StoreService entitlement fold")
struct StoreEntitlementFoldTests {
    /// A service with its `Transaction.updates` listener cancelled — the fold logic needs no
    /// live StoreKit, and detaching avoids an idle listener Task outliving the test.
    private func makeService() -> StoreService {
        let service = StoreService()
        service.shutdown()
        return service
    }

    @Test("granting an individual theme adds exactly that product")
    func grantIndividual() {
        let service = makeService()
        service.applyEntitlement(productID: StoreCatalog.Theme.ember, isRevoked: false)
        #expect(service.ownedThemeIDs == [StoreCatalog.Theme.ember])
    }

    @Test("granting a standalone reference theme adds exactly that product")
    func grantReferenceTheme() {
        let service = makeService()
        service.applyEntitlement(productID: StoreCatalog.Theme.solarized, isRevoked: false)
        #expect(service.ownedThemeIDs == [StoreCatalog.Theme.solarized])
    }

    @Test("granting the bundle unlocks every purchasable theme, including reference themes")
    func grantBundle() {
        let service = makeService()
        service.applyEntitlement(productID: StoreCatalog.Theme.bundleAll, isRevoked: false)
        #expect(service.ownedThemeIDs == StoreCatalog.Theme.purchasableIndividually)
        #expect(service.ownedThemeIDs.isSuperset(of: StoreCatalog.Theme.referenceIDs))
    }

    @Test("revoking the bundle removes every theme it granted")
    func revokeBundle() {
        let service = makeService()
        service.applyEntitlement(productID: StoreCatalog.Theme.bundleAll, isRevoked: false)
        service.applyEntitlement(productID: StoreCatalog.Theme.bundleAll, isRevoked: true)
        #expect(service.ownedThemeIDs.isEmpty)
    }

    @Test("revoking one individual theme leaves the rest of a bundle grant intact")
    func revokeIndividual() {
        let service = makeService()
        service.applyEntitlement(productID: StoreCatalog.Theme.bundleAll, isRevoked: false)
        service.applyEntitlement(productID: StoreCatalog.Theme.marine, isRevoked: true)
        #expect(service.ownedThemeIDs == StoreCatalog.Theme.purchasableIndividually.subtracting([StoreCatalog.Theme.marine]))
    }

    @Test("a consumable tip product grants no theme entitlement")
    func tipGrantsNothing() {
        let service = makeService()
        service.applyEntitlement(productID: StoreCatalog.Tip.coffee, isRevoked: false)
        #expect(service.ownedThemeIDs.isEmpty)
    }

    @Test("the entitlements-changed callback fires only on a real change")
    func callbackFiresOnChangeOnly() {
        let service = makeService()
        let count = MutableBox(0)
        service.onEntitlementsChanged = { count.value += 1 }

        service.applyEntitlement(productID: StoreCatalog.Theme.lavender, isRevoked: false)
        service.applyEntitlement(productID: StoreCatalog.Theme.lavender, isRevoked: false)   // already owned

        #expect(service.ownedThemeIDs == [StoreCatalog.Theme.lavender])
        #expect(count.value == 1)
    }
}
