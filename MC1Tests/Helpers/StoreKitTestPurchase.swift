import StoreKit
import MC1Services

/// Retries `StoreService.purchase` past the transient `StoreKitError.unknown` that storekitd raises
/// intermittently under SKTestSession churn (it surfaces as `.purchaseFailed`). A real purchase
/// failure is not masked — it throws on every attempt; other errors are not retried. Shared across
/// every SKTestSession suite so setup purchases don't flake under `make test-store`.
@MainActor
func purchaseWithRetry(
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
