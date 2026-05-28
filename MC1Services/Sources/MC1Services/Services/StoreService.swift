import Foundation
import StoreKit
import os

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

    /// Internal (not private) so `@testable` tests can assert listener teardown.
    private(set) var transactionListenerTask: Task<Void, Never>?
    private var loggedUnverifiedIDs: Set<UInt64> = []
    private let logger = Logger(subsystem: "com.mc1", category: "Store")

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
    public func load(productIDs: Set<String> = StoreCatalog.allProductIDs) async {
        loadState = .loading
        await processUnfinishedTransactions()
        do {
            let loaded = try await Product.products(for: productIDs)
            guard !loaded.isEmpty else {
                products = []
                loadState = .failed
                return
            }
            products = loaded
        } catch {
            logger.error("Product load failed: \(String(describing: error))")
            products = []
            loadState = .failed
            return
        }
        await walkCurrentEntitlements()
        loadState = .loaded
    }

    public func product(for productID: String) -> Product? {
        products.first { $0.id == productID }
    }

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

        switch result {
        case .success(let verification):
            switch verification {
            case .verified(let transaction):
                await walkCurrentEntitlements()
                await transaction.finish()
                return .purchased
            case .unverified:
                throw StoreServiceError.verificationFailed
            }
        case .userCancelled:
            return .userCancelled
        case .pending:
            return .pending
        @unknown default:
            throw StoreServiceError.purchaseFailed(reason: "Unhandled purchase result")
        }
    }

    public func restorePurchases() async throws {
        do {
            try await AppStore.sync()
        } catch is CancellationError {
            return   // view dismissal mid-sync is not an error; matches userCancelled posture
        } catch let error as StoreKitError {
            if let mapped = StoreServiceError.from(error) { throw mapped }
            return
        } catch {
            throw StoreServiceError.purchaseFailed(reason: String(describing: error))
        }
        await walkCurrentEntitlements()
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
        await walkCurrentEntitlements()
        await transaction.finish()
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
    /// no-op). Bundle ownership expands to every individual theme ID.
    private func walkCurrentEntitlements() async {
        var owned: Set<String> = []
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else {
                noteUnverified(result)
                continue
            }
            if transaction.productID == StoreCatalog.Theme.bundleAll {
                owned.formUnion(StoreCatalog.Theme.individualIDs)
            } else if StoreCatalog.Theme.individualIDs.contains(transaction.productID) {
                owned.insert(transaction.productID)
            }
            // Tip transactions are consumable and never appear in currentEntitlements.
        }
        ownedThemeIDs = owned
        onEntitlementsChanged?()
    }

    /// Logs an unverified transaction once per transaction ID, then drops it (Apple guidance).
    private func noteUnverified(_ result: VerificationResult<Transaction>) {
        guard case .unverified(let transaction, let error) = result else { return }
        if loggedUnverifiedIDs.insert(transaction.id).inserted {
            logger.warning("Dropping unverified transaction \(transaction.id): \(String(describing: error))")
        }
    }
}
