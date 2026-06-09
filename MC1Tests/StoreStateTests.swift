import Testing
@testable import MC1

@Suite("Store value-model types")
struct StoreValueModelTests {

    @Test("RestoreState distinguishes its five cases")
    func restoreStateEquatable() {
        #expect(RestoreState.idle == .idle)
        #expect(RestoreState.syncing != .completed)
        #expect(RestoreState.failed != .idle)
        #expect(RestoreState.cancelled != .completed)
        #expect(RestoreState.cancelled != .failed)
    }

    @Test("PendingPurchase carries productID and displayName")
    func pendingPurchaseFields() {
        let pending = PendingPurchase(productID: "io.pocketmesh.app.theme.marine", displayName: "Marine")
        #expect(pending.productID == "io.pocketmesh.app.theme.marine")
        #expect(pending.displayName == "Marine")
        #expect(pending == PendingPurchase(productID: "io.pocketmesh.app.theme.marine", displayName: "Marine"))
    }
}

import SwiftUI
import StoreKit
import StoreKitTest
@testable import MC1Services

@MainActor
@Suite("StoreState error mapping")
struct StoreStateErrorMappingTests {

    private func makeState() -> StoreState {
        let service = StoreService()
        service.shutdown()
        return StoreState(service: service)
    }

    @Test("every StoreServiceError maps to a non-empty localized message")
    func everyErrorMaps() {
        let state = makeState()
        let cases: [StoreServiceError] = [
            .productsNotLoaded, .productNotFound(productID: "x"),
            .purchaseFailed(reason: "boom"), .verificationFailed,
            .networkUnavailable, .storefrontUnavailable, .unsupported
        ]
        for error in cases {
            #expect(state.localizedMessage(for: error).isEmpty == false)
        }
    }

    @Test("purchaseFailed embeds the underlying reason")
    func purchaseFailedEmbedsReason() {
        let state = makeState()
        #expect(state.localizedMessage(for: .purchaseFailed(reason: "boom")).contains("boom"))
    }

    @Test("reconcilePendingPurchase is a no-op when there is no pending purchase")
    func reconcileNoPending() {
        let state = makeState()
        state.reconcilePendingPurchase()
        #expect(state.pendingPurchase == nil)
    }
}

@MainActor
@Suite("StoreState purchase/restore", .serialized, .enabled(if: StoreKitTestAvailability.servesProducts))
final class StoreStatePurchaseTests {
    let session: SKTestSession

    init() throws {
        session = try SKTestSession(configurationFileNamed: "MC1")
        session.disableDialogs = true
        session.askToBuyEnabled = false   // reset: SKTestSession leaks session flags across instances in-process
        session.clearTransactions()
    }

    deinit { session.clearTransactions() }

    @Test("buying the bundle sets no error and leaves no pending purchase")
    func purchaseBundleNoPending() async throws {
        let service = StoreService()
        await service.load()
        let state = StoreState(service: service)

        await state.purchase(productID: StoreCatalog.Theme.bundleAll) { try await $0.purchase() }

        #expect(state.errorMessage == nil)
        #expect(state.pendingPurchase == nil)
        #expect(service.ownedThemeIDs == StoreCatalog.Theme.bundledThemeIDs)
    }

    @Test("an Ask-to-Buy purchase sets a pending banner, then reconcile clears it on approval")
    func askToBuySetsAndClearsPending() async throws {
        session.askToBuyEnabled = true
        let service = StoreService()
        await service.load()
        let state = StoreState(service: service)

        await state.purchase(productID: StoreCatalog.Theme.bundleAll) { try await $0.purchase() }
        #expect(state.pendingPurchase?.productID == StoreCatalog.Theme.bundleAll)

        let pending = try #require(session.allTransactions().first {
            $0.productIdentifier == StoreCatalog.Theme.bundleAll
        })
        try session.approveAskToBuyTransaction(identifier: pending.identifier)

        try await waitUntil(timeout: .seconds(5)) {
            service.ownedThemeIDs == StoreCatalog.Theme.bundledThemeIDs
        }
        state.reconcilePendingPurchase()
        #expect(state.pendingPurchase == nil)
    }

    @Test("restore transitions syncing then completed")
    func restoreCompletes() async throws {
        let service = StoreService()
        await service.load()
        let state = StoreState(service: service)

        await state.restorePurchases()

        #expect(state.restoreState == .completed)
        #expect(state.errorMessage == nil)
    }

    @Test("purchase returns true on a verified .purchased outcome")
    func purchaseReturnsTrueOnPurchased() async throws {
        let service = StoreService()
        await service.load()
        let state = StoreState(service: service)

        let result = await state.purchase(productID: StoreCatalog.Theme.bundleAll) { try await $0.purchase() }

        #expect(result == true)
        #expect(state.errorMessage == nil)
        #expect(service.ownedThemeIDs == StoreCatalog.Theme.bundledThemeIDs)
    }

    @Test("purchase returns false on .userCancelled without setting an error or pending banner")
    func purchaseReturnsFalseOnUserCancel() async throws {
        let service = StoreService()
        await service.load()
        let state = StoreState(service: service)

        // SKTestSession exposes no `.userCancelled` `Product.PurchaseResult`; the purchase closure
        // stubs it so this branch is reachable from tests.
        let result = await state.purchase(productID: StoreCatalog.Theme.bundleAll) { _ in .userCancelled }

        #expect(result == false)
        #expect(state.errorMessage == nil)
        #expect(state.pendingPurchase == nil)
    }

    @Test("an unrelated .purchased does not clear an in-flight pending purchase")
    func purchaseDoesNotClearUnrelatedPending() async throws {
        // Ask-to-Buy on while the bundle is bought: outcome is .pending, banner is set.
        session.askToBuyEnabled = true
        let service = StoreService()
        await service.load()
        let state = StoreState(service: service)

        _ = await state.purchase(productID: StoreCatalog.Theme.bundleAll) { try await $0.purchase() }
        #expect(state.pendingPurchase?.productID == StoreCatalog.Theme.bundleAll)

        // Ask-to-Buy off so the next purchase completes immediately.
        session.askToBuyEnabled = false
        let coffeeResult = await state.purchase(productID: StoreCatalog.Tip.coffee) { try await $0.purchase() }

        #expect(coffeeResult == true)
        // Clearing the pending banner is gated on matching productID, so completing an
        // unrelated tip purchase leaves the bundle's banner intact.
        #expect(state.pendingPurchase?.productID == StoreCatalog.Theme.bundleAll)
    }

    @Test("restore that the user cancels maps to .cancelled, not .completed")
    func restoreUserCancelMapsToCancelledNotCompleted() async throws {
        let service = StoreService()
        await service.load()
        // AppStore.sync() cannot be made to throw userCancelled from SKTestSession; the seam
        // throws on its behalf so the `.cancelled` arm of `RestoreOutcome` is reachable.
        service.appStoreSyncForTesting = { throw StoreKitError.userCancelled }
        let state = StoreState(service: service)

        await state.restorePurchases()

        #expect(state.restoreState == .cancelled)
        #expect(state.errorMessage == nil)
    }
}
