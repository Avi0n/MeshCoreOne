import Testing
import SwiftUI
import StoreKit
import StoreKitTest
import MC1Services
@testable import MC1

/// SKTestSession-driven coverage for `RefundLinkSection.latestRefundableTransactionID()`.
/// Only the bundle is purchasable, so it is the only refundable product. `Transaction.latest(for:)`
/// retains refunded rows with `revocationDate` set; without the non-nil-revocationDate guard, the
/// "Request a refund" link stays visible after a refund and points at the just-revoked transaction.
@MainActor
@Suite("RefundLinkSection helpers", .serialized, .enabled(if: StoreKitTestAvailability.servesProducts))
final class RefundLinkSectionTests {
    let session: SKTestSession

    init() throws {
        session = try SKTestSession(configurationFileNamed: "MC1")
        session.disableDialogs = true
        // Reset Ask-to-Buy: SKTestSession does not clear this on storekitd across instances.
        session.askToBuyEnabled = false
        session.clearTransactions()
    }

    deinit { session.clearTransactions() }

    @Test("latestRefundableTransactionID excludes a refunded (revoked) bundle transaction")
    func latestRefundableTransactionIDExcludesRevoked() async throws {
        // Purchase the bundle via product.purchase() (synchronously consistent, unlike
        // session.buyProduct() which is eventually consistent under storekitd churn).
        let bundle = try #require(
            try await Product.products(for: [StoreCatalog.Theme.bundleAll]).first
        )
        try await purchaseUnfinished(bundle)

        // Confirm the helper finds the verified, non-revoked purchase before refunding so a
        // post-refund nil is provably from the revocation filter, not from a missing setup.
        let section = RefundLinkSection()
        let preRefundID = await section.latestRefundableTransactionID()
        #expect(preRefundID != nil)

        let txn = try #require(session.allTransactions().first {
            $0.productIdentifier == StoreCatalog.Theme.bundleAll
        })
        try session.refundTransaction(identifier: txn.identifier)

        // Wait for refund propagation: Transaction.latest's revocationDate is the field
        // RefundLinkSection.latestRefundableTransactionID guards on.
        try await waitUntil(timeout: .seconds(5)) {
            guard case .verified(let latest) =
                await Transaction.latest(for: StoreCatalog.Theme.bundleAll)
            else { return false }
            return latest.revocationDate != nil
        }

        let postRefundID = await section.latestRefundableTransactionID()
        #expect(postRefundID == nil)
    }

    /// Commits a purchase through `product.purchase()` and leaves the transaction unfinished;
    /// retries the transient `StoreKitError.unknown` storekitd raises under SKTestSession churn.
    /// Same shape as `StoreServiceTests.purchaseUnfinished` — duplicated locally because this
    /// suite is decoupled from `StoreService`.
    private func purchaseUnfinished(_ product: Product, attempts: Int = 4) async throws {
        for attempt in 1...attempts {
            do {
                let result = try await product.purchase()
                guard case .success = result else {
                    throw RefundLinkTestError.purchaseDidNotSucceed
                }
                return
            } catch let error as StoreKitError {
                guard case .unknown = error, attempt < attempts else { throw error }
            }
        }
    }
}

private enum RefundLinkTestError: Error { case purchaseDidNotSucceed }
