import Testing
import StoreKit
import StoreKitTest
@testable import MC1Services

@MainActor
@Suite("StoreService", .serialized, .enabled(if: StoreKitTestAvailability.servesProducts))
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

    @Test("the bundled configuration exposes all 16 products")
    func configExposesAllProducts() async throws {
        let products = try await Product.products(for: StoreCatalog.allProductIDs)
        #expect(products.count == 16)
    }

    @Test("load populates products and reports loaded")
    func loadPopulatesProducts() async throws {
        let service = StoreService()
        await service.load()
        #expect(service.loadState == .loaded)
        #expect(service.products.count == 16)
        #expect(service.product(for: StoreCatalog.Theme.ember) != nil)
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
        let ember = try #require(service.product(for: StoreCatalog.Theme.ember))

        let callbackCount = MutableBox(0)
        service.onEntitlementsChanged = { callbackCount.value += 1 }

        let outcome = try await purchaseWithRetry(ember, on: service)

        #expect(outcome == .purchased)
        #expect(service.ownedThemeIDs.contains(StoreCatalog.Theme.ember))
        #expect(callbackCount.value >= 1)
    }

    @Test("buying the bundle grants every purchasable theme")
    func purchaseBundleGrantsAllThemes() async throws {
        let service = StoreService()
        await service.load()
        let bundle = try #require(service.product(for: StoreCatalog.Theme.bundleAll))

        let outcome = try await purchaseWithRetry(bundle, on: service)

        #expect(outcome == .purchased)
        #expect(service.ownedThemeIDs == StoreCatalog.Theme.purchasableIndividually)
    }

    @Test("bundle ownership coexists with a previously bought individual theme")
    func bundleCoexistsWithIndividual() async throws {
        let service = StoreService()
        await service.load()
        let marine = try #require(service.product(for: StoreCatalog.Theme.marine))
        let bundle = try #require(service.product(for: StoreCatalog.Theme.bundleAll))

        _ = try await purchaseWithRetry(marine, on: service)
        _ = try await purchaseWithRetry(bundle, on: service)

        #expect(service.ownedThemeIDs == StoreCatalog.Theme.purchasableIndividually)
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

    @Test("an Ask-to-Buy-approved consumable fires onConsumablePurchased with the productID")
    func consumablePurchaseFiresOnConsumablePurchased() async throws {
        // `onConsumablePurchased` exists to clear a pending banner when an Ask-to-Buy tip is
        // approved out-of-band (the inline product.purchase() path never reaches it for inline
        // buys; only `applyTransactionUpdate` does, and that path only runs for transactions
        // delivered through `Transaction.updates`). This test exercises the production trigger.
        session.askToBuyEnabled = true
        let service = StoreService()
        await service.load()
        let coffee = try #require(service.product(for: StoreCatalog.Tip.coffee))

        let received = MutableBox<[String]>([])
        service.onConsumablePurchased = { productID in received.value.append(productID) }

        let outcome = try await purchaseWithRetry(coffee, on: service)
        #expect(outcome == .pending)

        let pendingTxn = try #require(session.allTransactions().first {
            $0.productIdentifier == StoreCatalog.Tip.coffee
        })
        try session.approveAskToBuyTransaction(identifier: pendingTxn.identifier)

        try await waitUntil(timeout: .seconds(5)) {
            received.value.contains(StoreCatalog.Tip.coffee)
        }
        #expect(received.value == [StoreCatalog.Tip.coffee])
    }

    @Test("refunding a theme revokes its entitlement via the listener")
    func refundRevokesTheme() async throws {
        let service = StoreService()
        await service.load()
        let ember = try #require(service.product(for: StoreCatalog.Theme.ember))
        _ = try await purchaseWithRetry(ember, on: service)
        #expect(service.ownedThemeIDs.contains(StoreCatalog.Theme.ember))

        let txn = try #require(session.allTransactions().first {
            $0.productIdentifier == StoreCatalog.Theme.ember
        })
        try session.refundTransaction(identifier: txn.identifier)

        try await waitUntil(timeout: .seconds(5)) {
            !service.ownedThemeIDs.contains(StoreCatalog.Theme.ember)
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
        #expect(service.ownedThemeIDs == StoreCatalog.Theme.purchasableIndividually)

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
        let fern = try #require(
            try await Product.products(for: [StoreCatalog.Theme.fern]).first
        )
        try await purchaseUnfinished(fern)

        // Guard against any residual lag before Transaction.unfinished reflects the committed sale.
        try await waitUntil(
            timeout: .seconds(5),
            pollingInterval: .milliseconds(100),
            "committed fern transaction did not surface in Transaction.unfinished"
        ) {
            await self.hasUnfinished(StoreCatalog.Theme.fern)
        }

        let service = StoreService()
        service.shutdown()   // detach the listener so only load()'s drain path can finish the transaction
        await service.load()

        #expect(service.ownedThemeIDs.contains(StoreCatalog.Theme.fern))

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
        let olive = try #require(service.product(for: StoreCatalog.Theme.olive))

        _ = try await purchaseWithRetry(olive, on: service)   // listener + purchase both walk
        let afterPurchase = service.ownedThemeIDs
        await service.load()                                     // walk again
        let afterReload = service.ownedThemeIDs

        #expect(afterPurchase == afterReload)
        #expect(afterReload == [StoreCatalog.Theme.olive])
    }

    // MARK: - SKTestSession robustness helpers

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
