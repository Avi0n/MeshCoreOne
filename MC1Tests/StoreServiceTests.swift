@testable import MC1Services
import StoreKit
import StoreKitTest
import Testing

@MainActor
@Suite("StoreService", .serialized, .enabled(if: StoreKitTestAvailability.servesProducts))
final class StoreServiceTests {
  /// Two spec behaviors are covered by code inspection, not automated tests, because
  /// SKTestSession cannot produce the required conditions:
  ///   1. Unverified-transaction drop + deduped warning log. SKTestSession only ever
  ///      produces VerificationResult.verified transactions, so the `.unverified` branch
  ///      in walkCurrentEntitlements / applyTransactionUpdate / processUnfinishedTransactions
  ///      is unreachable from a test. The production handling (noteUnverified) is verified by
  ///      inspecting StoreService and exercised in the sandbox pre-submission checklist.
  ///   2. CancellationError filtering in restorePurchases(). AppStore.sync() cannot be made to
  ///      throw CancellationError on demand; the `catch is CancellationError { return }` guard
  ///      is verified by inspection.
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

  @Test
  func `the bundled configuration exposes all seven sellable products`() async throws {
    let products = try await Product.products(for: StoreCatalog.sellableProductIDs)
    #expect(products.count == 7)
  }

  @Test
  func `load populates products and reports loaded`() async {
    let service = StoreService()
    await service.load()
    #expect(service.loadState == .loaded)
    #expect(service.products.count == 7)
    #expect(service.product(for: StoreCatalog.Theme.bundleAll) != nil)
  }

  @Test
  func `a clean account owns no themes after load`() async {
    let service = StoreService()
    await service.load()
    #expect(service.ownedThemeIDs.isEmpty)
  }

  @Test
  func `load with only unknown IDs fails`() async {
    let service = StoreService()
    await service.load(productIDs: ["io.pocketmesh.app.nonexistent"])
    #expect(service.loadState == .failed)
    #expect(service.products.isEmpty)
  }

  @Test
  func `shutdown cancels the transaction listener`() {
    let service = StoreService()
    #expect(service.transactionListenerTask != nil)
    service.shutdown()
    #expect(service.transactionListenerTask == nil)
  }

  @Test
  func `buying the bundle grants every theme, fires the callback, and finishes the transaction`() async throws {
    let service = StoreService()
    await service.load()
    let bundle = try #require(service.product(for: StoreCatalog.Theme.bundleAll))

    let callbackCount = MutableBox(0)
    service.onEntitlementsChanged = { callbackCount.value += 1 }

    let outcome = try await purchaseWithRetry(bundle, on: service)

    #expect(outcome == .purchased)
    #expect(service.ownedThemeIDs == StoreCatalog.Theme.bundledThemeIDs)
    #expect(callbackCount.value >= 1)
  }

  @Test
  func `buying a consumable tip succeeds without granting a theme entitlement`() async throws {
    let service = StoreService()
    await service.load()
    let coffee = try #require(service.product(for: StoreCatalog.Tip.coffee))

    let outcome = try await purchaseWithRetry(coffee, on: service)

    #expect(outcome == .purchased)
    #expect(service.ownedThemeIDs.isEmpty)
  }

  @Test
  func `an Ask-to-Buy-approved consumable fires onConsumablePurchased with the productID`() async throws {
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

  @Test
  func `refunding the bundle revokes its theme entitlements via the listener`() async throws {
    let service = StoreService()
    await service.load()
    let bundle = try #require(service.product(for: StoreCatalog.Theme.bundleAll))
    _ = try await purchaseWithRetry(bundle, on: service)
    #expect(service.ownedThemeIDs == StoreCatalog.Theme.bundledThemeIDs)

    let txn = try #require(session.allTransactions().first {
      $0.productIdentifier == StoreCatalog.Theme.bundleAll
    })
    try session.refundTransaction(identifier: txn.identifier)

    try await waitUntil(timeout: .seconds(5)) {
      service.ownedThemeIDs.isEmpty
    }
  }

  @Test
  func `load drains unfinished transactions and applies their entitlement`() async throws {
    // Simulate a purchase committed while no StoreService was running: buy directly and leave
    // the transaction unfinished. product.purchase() is synchronously consistent (unlike the
    // eventually-consistent session.buyProduct), so the transaction is committed before any
    // StoreService exists and load()'s drain path sees it deterministically.
    let bundle = try #require(
      try await Product.products(for: [StoreCatalog.Theme.bundleAll]).first
    )
    try await purchaseUnfinished(bundle)

    // Guard against any residual lag before Transaction.unfinished reflects the committed sale.
    try await waitUntil(
      timeout: .seconds(5),
      pollingInterval: .milliseconds(100),
      "committed bundle transaction did not surface in Transaction.unfinished"
    ) {
      await self.hasUnfinished(StoreCatalog.Theme.bundleAll)
    }

    let service = StoreService()
    service.shutdown() // detach the listener so only load()'s drain path can finish the transaction
    await service.load()

    #expect(service.ownedThemeIDs == StoreCatalog.Theme.bundledThemeIDs)

    var remainingUnfinished = 0
    for await _ in Transaction.unfinished {
      remainingUnfinished += 1
    }
    #expect(remainingUnfinished == 0)
  }

  @Test
  func `restore re-walks entitlements after AppStore.sync`() async throws {
    // Establish ownership via the synchronously-consistent product.purchase(), then build a
    // fresh service whose ownedThemeIDs starts empty and recover it through restore.
    let bundle = try #require(
      try await Product.products(for: [StoreCatalog.Theme.bundleAll]).first
    )
    try await purchaseUnfinished(bundle)

    let service = StoreService()
    service.shutdown() // isolate the restore path from the listener
    try await service.restorePurchases()

    #expect(service.ownedThemeIDs == StoreCatalog.Theme.bundledThemeIDs)
  }

  @Test
  func `Ask-to-Buy yields a pending outcome, then the approval grants entitlement`() async throws {
    session.askToBuyEnabled = true
    let service = StoreService()
    await service.load()
    let bundle = try #require(service.product(for: StoreCatalog.Theme.bundleAll))

    let outcome = try await purchaseWithRetry(bundle, on: service)
    #expect(outcome == .pending)
    #expect(service.ownedThemeIDs.isEmpty)

    let pending = try #require(session.allTransactions().first {
      $0.productIdentifier == StoreCatalog.Theme.bundleAll
    })
    try session.approveAskToBuyTransaction(identifier: pending.identifier)

    try await waitUntil(timeout: .seconds(5)) {
      service.ownedThemeIDs == StoreCatalog.Theme.bundledThemeIDs
    }
  }

  @Test
  func `listener and load applying the same transaction do not corrupt state`() async throws {
    let service = StoreService()
    await service.load()
    let bundle = try #require(service.product(for: StoreCatalog.Theme.bundleAll))

    _ = try await purchaseWithRetry(bundle, on: service) // listener + purchase both walk
    let afterPurchase = service.ownedThemeIDs
    await service.load() // walk again
    let afterReload = service.ownedThemeIDs

    #expect(afterPurchase == afterReload)
    #expect(afterReload == StoreCatalog.Theme.bundledThemeIDs)
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
      if case let .verified(txn) = result, txn.productID == productID { return true }
    }
    return false
  }
}
