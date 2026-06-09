import Foundation
import StoreKit

/// App-lifetime StoreKit 2 purchase engine. Radio-independent: owned directly by the app
/// (wired in a later plan), not by the per-connection `ServiceContainer`.
@Observable
@MainActor
public final class StoreService {
    public private(set) var products: [Product] = []
    public private(set) var ownedThemeIDs: Set<String> = []
    public private(set) var loadState: StoreLoadState = .idle

    /// Invoked on `@MainActor` after each entitlement walk (load, restore, transaction update).
    /// A later plan registers `ThemeService` here to drive theme-revert reactivity.
    public var onEntitlementsChanged: (@MainActor () -> Void)?

    /// Invoked on `@MainActor` after a consumable transaction (tip) is finished via
    /// `applyTransactionUpdate`. Consumables never appear in `Transaction.currentEntitlements`,
    /// so they cannot be observed via `ownedThemeIDs` — `StoreState` uses this hook to clear
    /// `pendingPurchase` when an Ask-to-Buy tip is approved out-of-band.
    public var onConsumablePurchased: (@MainActor (String) -> Void)?

    /// Invoked on `@MainActor` whenever `load()` transitions to `.failed` (product fetch
    /// threw or returned empty). `StoreState` uses this to surface an actionable error message
    /// so the initial load failure isn't silent — the user would otherwise see disabled '—'
    /// price buttons with no explanation, and the existing `.errorAlert` retry would never fire.
    public var onLoadStateFailed: (@MainActor () -> Void)?

    /// Internal (not private) so `@testable` tests can assert listener teardown.
    private(set) var transactionListenerTask: Task<Void, Never>?
    private var loggedUnverifiedIDs: Set<UInt64> = []
    /// Caps the unverified-log dedup set on an app-lifetime service. Distinct unverified
    /// transaction IDs are tiny in practice; this is a backstop against unbounded growth.
    private static let maxLoggedUnverifiedIDs = 256
    /// Persisted so in-app-purchase diagnostics survive into the Settings "Export Debug Logs"
    /// output, for triaging purchase failures reported from TestFlight. `[IAP]`-prefixed lines
    /// trace the purchase and entitlement flow.
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "Store")

    /// Test seam: when non-nil, `restorePurchases()` invokes this closure instead of
    /// `AppStore.sync()`. Exists because SKTestSession exposes no way to drive
    /// `AppStore.sync()` into either of `restorePurchases()`'s two cancel paths
    /// (`CancellationError` or `StoreKitError.userCancelled`), and without this the
    /// `.cancelled` arm of `RestoreOutcome` is unreachable from tests. Nil in production.
    internal var appStoreSyncForTesting: (@Sendable () async throws -> Void)?

    public init() {
        transactionListenerTask = Task { [weak self] in
            await self?.observeTransactionUpdates()
        }
    }

    /// Cancels the listener task. There is no reliable SwiftUI app-teardown hook to call this
    /// from, so in production the listener dies with the process; this exists for deterministic
    /// test teardown. `deinit` is avoided because a non-isolated `deinit` cannot touch
    /// `@MainActor` state under strict concurrency, and `isolated deinit` requires iOS 18.4+
    /// (the project floor is 18.0).
    public func shutdown() {
        transactionListenerTask?.cancel()
        transactionListenerTask = nil
    }

    /// Loads the catalog and walks current entitlements. Non-throwing: surfaces failure via
    /// `loadState`. `productIDs` defaults to the full catalog; tests override it to exercise
    /// the empty-result path.
    public func load(productIDs: Set<String> = StoreCatalog.sellableProductIDs) async {
        loadState = .loading
        await processUnfinishedTransactions()
        do {
            let loaded = try await Product.products(for: productIDs)
            guard !loaded.isEmpty else {
                logger.error("[IAP] load failed: 0 products returned for \(productIDs.count) IDs")
                products = []
                loadState = .failed
                onLoadStateFailed?()
                return
            }
            products = loaded
        } catch {
            logger.error("[IAP] load failed: \(String(describing: error))")
            products = []
            loadState = .failed
            onLoadStateFailed?()
            return
        }
        await walkCurrentEntitlements()
        loadState = .loaded
        logger.notice("[IAP] load complete: \(products.count) products, owned=\(ownedThemeIDs.count)")
    }

    public func product(for productID: String) -> Product? {
        products.first { $0.id == productID }
    }

    /// Initiates a purchase via `Product.purchase()` and processes the result. Retained for the
    /// `SKTestSession` suite, which cannot drive SwiftUI's `@Environment(\.purchase)`. The
    /// production view path initiates through that environment action instead (see
    /// `completePurchase(_:)`), so StoreKit resolves the confirmation scene from the view
    /// environment rather than a bare `UIWindowScene` lookup.
    public func purchase(_ product: Product) async throws -> StorePurchaseOutcome {
        let result: Product.PurchaseResult
        do {
            result = try await product.purchase()
        } catch let error as StoreKitError {
            if let mapped = StoreServiceError.from(error) { throw mapped }
            return .userCancelled
        } catch {
            throw StoreServiceError.purchaseFailed(reason: String(describing: error))
        }
        return try await completePurchase(result)
    }

    /// Processes a `Product.PurchaseResult` the caller already obtained: grants the entitlement
    /// from the verified transaction (rather than re-reading `currentEntitlements`, which can lag),
    /// finishes it, and maps to an outcome. The production view path obtains the result through
    /// SwiftUI's `@Environment(\.purchase)` action so the confirmation scene is resolved from the
    /// view environment; a bare `product.purchase()` relies on implicit foreground-scene lookup,
    /// which returns a spurious `.userCancelled` when the active scene churns (e.g. lifecycle
    /// reconciliation firing while the purchase sheet is presented).
    public func completePurchase(_ result: Product.PurchaseResult) async throws -> StorePurchaseOutcome {
        switch result {
        case .success(let verification):
            switch verification {
            case .verified(let transaction):
                logger.notice("[IAP] purchase verified product=\(transaction.productID)")
                apply(transaction)
                await transaction.finish()
                return .purchased
            case .unverified(let transaction, let error):
                logger.error("[IAP] purchase unverified product=\(transaction.productID): \(String(describing: error))")
                throw StoreServiceError.verificationFailed
            }
        case .userCancelled:
            logger.notice("[IAP] purchase userCancelled")
            return .userCancelled
        case .pending:
            logger.notice("[IAP] purchase pending (ask-to-buy)")
            return .pending
        @unknown default:
            throw StoreServiceError.purchaseFailed(reason: "Unhandled purchase result")
        }
    }

    @discardableResult
    public func restorePurchases() async throws -> RestoreOutcome {
        do {
            if let appStoreSyncForTesting {
                try await appStoreSyncForTesting()
            } else {
                try await AppStore.sync()
            }
        } catch is CancellationError {
            return .cancelled   // view dismissal mid-sync is not an error; surface as cancel.
        } catch let error as StoreKitError {
            if let mapped = StoreServiceError.from(error) {
                logger.error("[IAP] restore failed: \(String(describing: error))")
                throw mapped
            }
            // .userCancelled (mapped to nil by StoreServiceError.from) lands here — it is a
            // user choice, not a successful restore. Surface as cancel so the caller does not
            // fire a success haptic for a dismissed iCloud password sheet.
            return .cancelled
        } catch {
            logger.error("[IAP] restore failed: \(String(describing: error))")
            throw StoreServiceError.purchaseFailed(reason: String(describing: error))
        }
        await walkCurrentEntitlements()
        logger.notice("[IAP] restore complete, owned=\(ownedThemeIDs.count)")
        return .completed
    }

    nonisolated private func observeTransactionUpdates() async {
        for await update in Transaction.updates {
            await applyTransactionUpdate(update)
        }
    }

    private func applyTransactionUpdate(_ result: VerificationResult<Transaction>) async {
        guard case .verified(let transaction) = result else {
            noteUnverified(result)
            return
        }
        if transaction.revocationDate != nil {
            // A refund or revocation is rare and the authoritative remaining set is whatever
            // currentEntitlements reports, so rebuild from it rather than fold the change in.
            await walkCurrentEntitlements()
        } else {
            apply(transaction)
        }
        await transaction.finish()
        if transaction.productType == .consumable {
            onConsumablePurchased?(transaction.productID)
        }
    }

    /// Finishes any transactions left unfinished while the app was closed (renewals,
    /// Ask-to-Buy approvals, refunds, cross-device purchases). Their entitlement state is
    /// applied by the subsequent `walkCurrentEntitlements()` call in `load()`.
    private func processUnfinishedTransactions() async {
        for await result in Transaction.unfinished {
            guard case .verified(let transaction) = result else {
                noteUnverified(result)
                continue
            }
            await transaction.finish()
        }
    }

    /// Rebuilds `ownedThemeIDs` from scratch each call (idempotent — double application is a
    /// no-op). Bundle ownership expands to every purchasable theme ID.
    private func walkCurrentEntitlements() async {
        var owned: Set<String> = []
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else {
                noteUnverified(result)
                continue
            }
            owned.formUnion(affectedThemeIDs(forProductID: transaction.productID))
            // Tip transactions are consumable and never appear in currentEntitlements.
        }
        ownedThemeIDs = owned
        logger.notice("[IAP] ownedThemeIDs count=\(ownedThemeIDs.count)")
        onEntitlementsChanged?()
    }

    /// The theme product IDs an entitlement for `productID` confers: the bundle unlocks every
    /// bundled theme, and anything else (tips) unlocks none. Themes are sold only as the bundle,
    /// so no standalone theme entitlement is ever granted.
    private func affectedThemeIDs(forProductID productID: String) -> Set<String> {
        productID == StoreCatalog.Theme.bundleAll ? StoreCatalog.Theme.bundledThemeIDs : []
    }

    /// Folds a verified transaction's entitlement into `ownedThemeIDs` directly, without re-reading
    /// `Transaction.currentEntitlements` — which can lag or return empty immediately after a
    /// purchase, leaving the just-bought theme locked. Grant paths (`purchase`, Ask-to-Buy
    /// approval) use this; revocation does not, since a refund rebuilds from currentEntitlements.
    private func apply(_ transaction: Transaction) {
        applyEntitlement(productID: transaction.productID, isRevoked: transaction.revocationDate != nil)
    }

    /// Additively grants or revokes the theme entitlements a product confers. Internal so the fold
    /// logic is unit-testable without StoreKit. Fires `onEntitlementsChanged` only on a real change.
    func applyEntitlement(productID: String, isRevoked: Bool) {
        let affected = affectedThemeIDs(forProductID: productID)
        logger.notice("[IAP] entitlement \(isRevoked ? "revoked" : "granted") product=\(productID) affected=\(affected.count)")
        guard !affected.isEmpty else { return }
        var owned = ownedThemeIDs
        if isRevoked {
            owned.subtract(affected)
        } else {
            owned.formUnion(affected)
        }
        guard owned != ownedThemeIDs else { return }
        ownedThemeIDs = owned
        onEntitlementsChanged?()
    }

    /// Logs an unverified transaction once per transaction ID, then drops it (Apple guidance).
    private func noteUnverified(_ result: VerificationResult<Transaction>) {
        guard case .unverified(let transaction, let error) = result else { return }
        if loggedUnverifiedIDs.count >= Self.maxLoggedUnverifiedIDs {
            loggedUnverifiedIDs.removeAll(keepingCapacity: true)
        }
        if loggedUnverifiedIDs.insert(transaction.id).inserted {
            logger.warning("Dropping unverified transaction \(transaction.id): \(String(describing: error))")
        }
    }
}
