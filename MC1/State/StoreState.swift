import MC1Services
import StoreKit
import SwiftUI

/// View-model wrapper over `StoreService` for the Support Development screen. Owned by `AppState`.
/// Presents the project-standard `errorMessage: String?` (surfaced via `.errorAlert`) and maps
/// `StoreServiceError` (English, from MC1Services) to localized copy at this layer.
@Observable
@MainActor
final class StoreState {
  let service: StoreService

  var errorMessage: String?
  var restoreState: RestoreState = .idle
  var pendingPurchase: PendingPurchase?

  /// Persisted so purchase diagnostics reach the Settings "Export Debug Logs" output for
  /// triaging failures reported from TestFlight. `StoreService` logs the StoreKit-side flow;
  /// this logs the view-driven entry, guards, and outcome.
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "Store")

  init(service: StoreService) {
    self.service = service
    // Out-of-band consumable resolution: an Ask-to-Buy-approved tip is finished by
    // `applyTransactionUpdate` but does not enter `ownedThemeIDs`, so `reconcilePendingPurchase`
    // (which only checks ownership) cannot clear the pending banner. This callback fires
    // after the tip's `transaction.finish()` so the banner clears immediately.
    service.onConsumablePurchased = { [weak self] productID in
      guard let self else { return }
      if pendingPurchase?.productID == productID { pendingPurchase = nil }
    }
    // Surface load failures as an alert so the user can see why the catalog didn't load
    // (network down, etc.) and use the `ErrorAlertModifier` retry button. Without this,
    // a failed initial load is silent — the Support screen shows disabled '—' buttons
    // with no signal that retry is the right action.
    service.onLoadStateFailed = { [weak self] in
      guard let self else { return }
      errorMessage = localizedMessage(for: .productsNotLoaded)
    }
  }

  /// Returns `true` only on `.purchased`; callers that gate post-success UI (e.g. ContributionRow's
  /// "thank you" animation) check this to avoid celebrating cancels, failures, or Ask-to-Buy pending.
  /// `purchase` performs the StoreKit purchase. The view passes SwiftUI's `@Environment(\.purchase)`
  /// action so StoreKit resolves the confirmation scene from the view environment, which is robust
  /// against the foreground-scene churn (lifecycle reconciliation during sheet presentation) that
  /// makes a bare `Product.purchase()` return a spurious `.userCancelled` on iOS 18.2+. Taking it as
  /// a closure keeps this method testable, since `PurchaseAction` cannot be constructed off a view.
  @discardableResult
  func purchase(
    productID: String,
    purchase: (Product) async throws -> Product.PurchaseResult
  ) async -> Bool {
    guard service.loadState == .loaded else {
      // Without this guard, an empty `products` array (because load failed/never finished)
      // would surface `.productNotFound` instead of the truer `.productsNotLoaded`, which
      // the ErrorAlertModifier retry button knows how to resolve via `service.load()`.
      logger.error("[IAP] purchase blocked: products not loaded (loadState=\(String(describing: service.loadState)))")
      errorMessage = localizedMessage(for: .productsNotLoaded)
      return false
    }
    guard let product = service.product(for: productID) else {
      logger.error("[IAP] purchase blocked: product not found product=\(productID)")
      errorMessage = localizedMessage(for: .productNotFound(productID: productID))
      return false
    }
    logger.notice("[IAP] purchase requested product=\(productID)")
    do {
      let result = try await purchase(product)
      let outcome = try await service.completePurchase(result)
      switch outcome {
      case .purchased:
        // Clear pendingPurchase only when the just-completed product matches; an unrelated
        // .purchased (e.g. tipping Coffee while Ember Ask-to-Buy is still in flight) must
        // not wipe a banner for a different in-flight purchase.
        if pendingPurchase?.productID == productID { pendingPurchase = nil }
        return true
      case .userCancelled:
        return false
      case .pending:
        pendingPurchase = PendingPurchase(productID: productID, displayName: product.displayName)
        return false
      }
    } catch let error as StoreServiceError {
      logger.error("[IAP] purchase failed product=\(productID): \(error.errorDescription ?? "\(error)")")
      errorMessage = localizedMessage(for: error)
      return false
    } catch let error as StoreKitError {
      // .userCancelled maps to nil and stays a silent cancel; anything else is a real failure.
      if let mapped = StoreServiceError.from(error) {
        logger.error("[IAP] purchase StoreKit error product=\(productID): \(String(describing: error))")
        errorMessage = localizedMessage(for: mapped)
      }
      return false
    } catch {
      logger.error("[IAP] purchase error product=\(productID): \(error.localizedDescription)")
      errorMessage = localizedMessage(for: .purchaseFailed(reason: error.localizedDescription))
      return false
    }
  }

  func restorePurchases() async {
    restoreState = .syncing
    do {
      switch try await service.restorePurchases() {
      case .completed: restoreState = .completed
      case .cancelled: restoreState = .cancelled
      }
    } catch let error as StoreServiceError {
      errorMessage = localizedMessage(for: error)
      restoreState = .failed
    } catch {
      errorMessage = localizedMessage(for: .purchaseFailed(reason: error.localizedDescription))
      restoreState = .failed
    }
  }

  /// Clears the pending banner once the pending product's entitlement has arrived. Driven by the
  /// view's `.onChange(of: service.ownedThemeIDs)`. The bundle expands to every purchasable theme
  /// ID (its own product ID never appears in `ownedThemeIDs`), so it resolves via a superset check.
  func reconcilePendingPurchase() {
    guard let pending = pendingPurchase else { return }
    let owned = service.ownedThemeIDs
    let isResolved = pending.productID == StoreCatalog.Theme.bundleAll
      ? owned.isSuperset(of: StoreCatalog.Theme.bundledThemeIDs)
      : owned.contains(pending.productID)
    if isResolved { pendingPurchase = nil }
  }

  /// Internal (not private) as a testability seam (see `StoreStateErrorMappingTests`).
  /// Delegates to the shared `StoreServiceError.userFacingMessage` mapping.
  func localizedMessage(for error: StoreServiceError) -> String {
    error.userFacingMessage
  }
}
