import Testing
import StoreKit
import StoreKitTest
@testable import MC1Services

@MainActor
@Suite("StoreService", .serialized)
final class StoreServiceTests {
    // Two spec behaviors are covered by code inspection, not automated tests, because
    // SKTestSession cannot produce the required conditions:
    //   1. Unverified-transaction drop + deduped warning log. SKTestSession only ever
    //      produces VerificationResult.verified transactions, so the `.unverified` branch
    //      in walkCurrentEntitlements / applyTransactionUpdate / processUnfinishedTransactions
    //      is unreachable from a test. The production handling (noteUnverified) is verified by
    //      inspecting StoreService and exercised in the sandbox pre-submission checklist.
    //   2. CancellationError filtering in restorePurchases(). AppStore.sync() cannot be made to
    //      throw CancellationError on demand; the `catch is CancellationError { return }` guard
    //      is verified by inspection.
    let session: SKTestSession

    init() throws {
        session = try SKTestSession(configurationFileNamed: "MC1")
        session.disableDialogs = true
        // Keep Ask-to-Buy off by default; askToBuyPendingThenApproved opts in, and the
        // setting otherwise persists across SKTestSession instances in the same process.
        session.askToBuyEnabled = false
        session.clearTransactions()
    }

    deinit { session.clearTransactions() }

    @Test("the bundled configuration exposes all 14 products")
    func configExposesAllProducts() async throws {
        let products = try await Product.products(for: StoreCatalog.allProductIDs)
        #expect(products.count == 14)
    }

    @Test("load populates products and reports loaded")
    func loadPopulatesProducts() async throws {
        let service = StoreService()
        await service.load()
        #expect(service.loadState == .loaded)
        #expect(service.products.count == 14)
        #expect(service.product(for: StoreCatalog.Theme.nightops) != nil)
    }

    @Test("a clean account owns no themes after load")
    func cleanAccountOwnsNothing() async throws {
        let service = StoreService()
        await service.load()
        #expect(service.ownedThemeIDs.isEmpty)
    }

    @Test("load with only unknown IDs fails")
    func loadEmptyFails() async throws {
        let service = StoreService()
        await service.load(productIDs: ["io.pocketmesh.app.nonexistent"])
        #expect(service.loadState == .failed)
        #expect(service.products.isEmpty)
    }

    @Test("shutdown cancels the transaction listener")
    func shutdownCancelsListener() async throws {
        let service = StoreService()
        #expect(service.transactionListenerTask != nil)
        service.shutdown()
        #expect(service.transactionListenerTask == nil)
    }

    @Test("buying a theme grants its entitlement and finishes the transaction")
    func purchaseThemeGrantsEntitlement() async throws {
        let service = StoreService()
        await service.load()
        let nightOps = try #require(service.product(for: StoreCatalog.Theme.nightops))

        let callbackCount = MutableBox(0)
        service.onEntitlementsChanged = { callbackCount.value += 1 }

        let outcome = try await purchaseWithRetry(nightOps, on: service)

        #expect(outcome == .purchased)
        #expect(service.ownedThemeIDs.contains(StoreCatalog.Theme.nightops))
        #expect(callbackCount.value >= 1)
    }

    @Test("buying the bundle grants all seven individual themes")
    func purchaseBundleGrantsAllThemes() async throws {
        let service = StoreService()
        await service.load()
        let bundle = try #require(service.product(for: StoreCatalog.Theme.bundleAll))

        let outcome = try await purchaseWithRetry(bundle, on: service)

        #expect(outcome == .purchased)
        #expect(service.ownedThemeIDs == StoreCatalog.Theme.individualIDs)
    }

    @Test("bundle ownership coexists with a previously bought individual theme")
    func bundleCoexistsWithIndividual() async throws {
        let service = StoreService()
        await service.load()
        let marine = try #require(service.product(for: StoreCatalog.Theme.marine))
        let bundle = try #require(service.product(for: StoreCatalog.Theme.bundleAll))

        _ = try await purchaseWithRetry(marine, on: service)
        _ = try await purchaseWithRetry(bundle, on: service)

        #expect(service.ownedThemeIDs == StoreCatalog.Theme.individualIDs)
    }

    @Test("buying a consumable tip succeeds without granting a theme entitlement")
    func purchaseTipGrantsNoEntitlement() async throws {
        let service = StoreService()
        await service.load()
        let coffee = try #require(service.product(for: StoreCatalog.Tip.coffee))

        let outcome = try await purchaseWithRetry(coffee, on: service)

        #expect(outcome == .purchased)
        #expect(service.ownedThemeIDs.isEmpty)
    }

    @Test("refunding a theme revokes its entitlement via the listener")
    func refundRevokesTheme() async throws {
        let service = StoreService()
        await service.load()
        let nightOps = try #require(service.product(for: StoreCatalog.Theme.nightops))
        _ = try await purchaseWithRetry(nightOps, on: service)
        #expect(service.ownedThemeIDs.contains(StoreCatalog.Theme.nightops))

        let txn = try #require(session.allTransactions().first {
            $0.productIdentifier == StoreCatalog.Theme.nightops
        })
        try session.refundTransaction(identifier: txn.identifier)

        try await waitUntil(timeout: .seconds(5)) {
            !service.ownedThemeIDs.contains(StoreCatalog.Theme.nightops)
        }
    }

    @Test("refunding the bundle leaves an independently bought theme intact")
    func refundBundleKeepsIndividual() async throws {
        let service = StoreService()
        await service.load()
        let marine = try #require(service.product(for: StoreCatalog.Theme.marine))
        let bundle = try #require(service.product(for: StoreCatalog.Theme.bundleAll))
        _ = try await purchaseWithRetry(marine, on: service)
        _ = try await purchaseWithRetry(bundle, on: service)
        #expect(service.ownedThemeIDs == StoreCatalog.Theme.individualIDs)

        let bundleTxn = try #require(session.allTransactions().first {
            $0.productIdentifier == StoreCatalog.Theme.bundleAll
        })
        try session.refundTransaction(identifier: bundleTxn.identifier)

        try await waitUntil(timeout: .seconds(5)) {
            service.ownedThemeIDs == [StoreCatalog.Theme.marine]
        }
    }

    @Test("load drains unfinished transactions and applies their entitlement")
    func loadDrainsUnfinished() async throws {
        // Simulate a purchase committed while no StoreService was running: buy directly and leave
        // the transaction unfinished. product.purchase() is synchronously consistent (unlike the
        // eventually-consistent session.buyProduct), so the transaction is committed before any
        // StoreService exists and load()'s drain path sees it deterministically.
        let topographer = try #require(
            try await Product.products(for: [StoreCatalog.Theme.topographer]).first
        )
        try await purchaseUnfinished(topographer)

        // Guard against any residual lag before Transaction.unfinished reflects the committed sale.
        try await waitUntil(
            timeout: .seconds(5),
            pollingInterval: .milliseconds(100),
            "committed topographer transaction did not surface in Transaction.unfinished"
        ) {
            await self.hasUnfinished(StoreCatalog.Theme.topographer)
        }

        let service = StoreService()
        service.shutdown()   // detach the listener so only load()'s drain path can finish the transaction
        await service.load()

        #expect(service.ownedThemeIDs.contains(StoreCatalog.Theme.topographer))

        var remainingUnfinished = 0
        for await _ in Transaction.unfinished { remainingUnfinished += 1 }
        #expect(remainingUnfinished == 0)
    }

    @Test("restore re-walks entitlements after AppStore.sync")
    func restoreRecoversEntitlements() async throws {
        // Establish ownership via the synchronously-consistent product.purchase(), then build a
        // fresh service whose ownedThemeIDs starts empty and recover it through restore.
        let sakura = try #require(
            try await Product.products(for: [StoreCatalog.Theme.sakura]).first
        )
        try await purchaseUnfinished(sakura)

        let service = StoreService()
        service.shutdown()   // isolate the restore path from the listener
        try await service.restorePurchases()

        #expect(service.ownedThemeIDs.contains(StoreCatalog.Theme.sakura))
    }

    @Test("Ask-to-Buy yields a pending outcome, then the approval grants entitlement")
    func askToBuyPendingThenApproved() async throws {
        session.askToBuyEnabled = true
        let service = StoreService()
        await service.load()
        let lavender = try #require(service.product(for: StoreCatalog.Theme.lavender))

        let outcome = try await purchaseWithRetry(lavender, on: service)
        #expect(outcome == .pending)
        #expect(!service.ownedThemeIDs.contains(StoreCatalog.Theme.lavender))

        let pending = try #require(session.allTransactions().first {
            $0.productIdentifier == StoreCatalog.Theme.lavender
        })
        try session.approveAskToBuyTransaction(identifier: pending.identifier)

        try await waitUntil(timeout: .seconds(5)) {
            service.ownedThemeIDs.contains(StoreCatalog.Theme.lavender)
        }
    }

    @Test("listener and load applying the same transaction do not corrupt state")
    func listenerLoadIdempotence() async throws {
        let service = StoreService()
        await service.load()
        let tactical = try #require(service.product(for: StoreCatalog.Theme.tactical))

        _ = try await purchaseWithRetry(tactical, on: service)   // listener + purchase both walk
        let afterPurchase = service.ownedThemeIDs
        await service.load()                                     // walk again
        let afterReload = service.ownedThemeIDs

        #expect(afterPurchase == afterReload)
        #expect(afterReload == [StoreCatalog.Theme.tactical])
    }

    // MARK: - SKTestSession robustness helpers

    /// Retries `StoreService.purchase` past the transient `StoreKitError.unknown` that storekitd
    /// raises intermittently under SKTestSession churn (it surfaces as `.purchaseFailed`). A real
    /// purchase failure is not masked — it throws on every attempt; other errors are not retried.
    private func purchaseWithRetry(
        _ product: Product,
        on service: StoreService,
        attempts: Int = 4
    ) async throws -> StorePurchaseOutcome {
        for attempt in 1...attempts {
            do {
                return try await service.purchase(product)
            } catch let error as StoreServiceError {
                guard case .purchaseFailed = error, attempt < attempts else { throw error }
            }
        }
        throw StoreServiceError.purchaseFailed(reason: "purchase retries exhausted")
    }

    /// Commits a purchase through the synchronously-consistent `product.purchase()` (unlike the
    /// eventually-consistent `session.buyProduct`) and deliberately leaves the transaction
    /// unfinished, so a later `load()` or `restorePurchases()` sees it deterministically. Retries
    /// the transient `StoreKitError.unknown` that storekitd raises under SKTestSession churn.
    private func purchaseUnfinished(_ product: Product, attempts: Int = 4) async throws {
        for attempt in 1...attempts {
            do {
                let result = try await product.purchase()
                guard case .success = result else {
                    throw StoreServiceError.purchaseFailed(reason: "setup purchase did not succeed: \(result)")
                }
                return
            } catch let error as StoreKitError {
                guard case .unknown = error, attempt < attempts else { throw error }
            }
        }
    }

    /// True once `productID` has an unfinished transaction, so a single `load()` sees it on the
    /// drain path.
    private func hasUnfinished(_ productID: String) async -> Bool {
        for await result in Transaction.unfinished {
            if case .verified(let txn) = result, txn.productID == productID { return true }
        }
        return false
    }
}
