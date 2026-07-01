import MC1Services
import StoreKit
import SwiftUI

/// "Request a refund" link for the owned theme bundle transaction. Resolved via
/// `Transaction.latest(for:)`; hidden when the bundle is not owned. Only the bundle is
/// purchasable, so only the bundle is refundable; individual themes and tips are excluded
/// (Apple provides no in-app refund flow for consumables).
struct RefundLinkSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  @State private var refundTransactionID: StoreKit.Transaction.ID?
  @State private var showingRefundSheet = false

  private var storeState: StoreState {
    appState.storeState
  }

  var body: some View {
    Group {
      if let transactionID = refundTransactionID {
        Section {
          Button(L10n.Settings.Support.Refund.link) { showingRefundSheet = true }
            .refundRequestSheet(for: transactionID, isPresented: $showingRefundSheet)
        }
        .themedRowBackground(theme)
      }
    }
    .task(id: storeState.service.ownedThemeIDs) {
      refundTransactionID = await latestRefundableTransactionID()
    }
  }

  /// The verified, non-revoked bundle transaction's ID, or nil if none. Only the bundle is
  /// purchasable, so it is the only refundable product. `Transaction.latest(for:)` keeps
  /// refunded/revoked rows (sets `revocationDate` but doesn't drop them), so the filter must
  /// exclude any non-nil `revocationDate` — otherwise the "Request a refund" link stays visible
  /// after a refund and points at the just-revoked transaction, which then returns
  /// `RefundRequestError.duplicateRequest`.
  /// Internal (not private) for `@testable` access from `RefundLinkSectionTests`.
  func latestRefundableTransactionID() async -> StoreKit.Transaction.ID? {
    guard case let .verified(transaction) = await Transaction.latest(for: StoreCatalog.Theme.bundleAll),
          transaction.revocationDate == nil
    else { return nil }
    return transaction.id
  }
}
