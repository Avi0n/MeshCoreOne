@testable import MC1Services
import StoreKit
import StoreKitTest

private let storeKitSessionSettleTimeout: Duration = .seconds(5)

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

/// Clears the test session and waits until `Transaction.currentEntitlements` is empty.
/// `clearTransactions()` lags; buying an already-owned non-consumable skips Ask to Buy.
@MainActor
func waitForClearedStoreKitSession(_ session: SKTestSession) async throws {
  session.askToBuyEnabled = false
  session.clearTransactions()
  try await waitUntil(
    timeout: storeKitSessionSettleTimeout,
    "SKTestSession.clearTransactions left currentEntitlements populated"
  ) {
    await storeKitCurrentEntitlementsAreEmpty()
  }
}

/// Waits until `SKTestSession` lists a transaction for `productID`.
/// Pending Ask to Buy rows can lag `Product.purchase()`.
@MainActor
func waitForTestTransaction(
  in session: SKTestSession,
  productID: String
) async throws {
  try await waitUntil(
    timeout: storeKitSessionSettleTimeout,
    "SKTestSession.allTransactions did not include \(productID)"
  ) {
    session.allTransactions().contains { $0.productIdentifier == productID }
  }
}

@MainActor
private func storeKitCurrentEntitlementsAreEmpty() async -> Bool {
  for await _ in Transaction.currentEntitlements {
    return false
  }
  return true
}
