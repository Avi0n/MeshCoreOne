package one.meshcore.shared.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillingDecisionTest {
    private val bundle = OwnedPurchase(
        listOf(StoreCatalog.THEME_BUNDLE),
        "token",
        PurchaseState.PURCHASED,
        isAcknowledged = false,
    )

    @Test
    fun deliveryEmitsPurchasesOnlyOnOkAndNonEmpty() {
        assertEquals(listOf(bundle), BillingDecision.deliveryFor(responseOk = true, purchases = listOf(bundle)))
        assertNull(BillingDecision.deliveryFor(responseOk = true, purchases = emptyList()))
        assertNull(BillingDecision.deliveryFor(responseOk = false, purchases = listOf(bundle)))
    }

    @Test
    fun productQueryIsAvailableOnlyOnOk() {
        val product = ProductDetails("id", "title", "", "$1")
        val ok = BillingDecision.productQueryFor(responseOk = true, products = listOf(product))
        assertEquals(ProductQueryResult.Available(listOf(product)), ok)

        val failed = BillingDecision.productQueryFor(responseOk = false, products = listOf(product))
        assertTrue(failed is ProductQueryResult.Unavailable)
    }

    @Test
    fun queryResultIsAvailableOnlyOnOk() {
        val ok = BillingDecision.queryResultFor(responseOk = true, purchases = listOf(bundle))
        assertEquals(PurchaseQueryResult.Available(listOf(bundle)), ok)

        val failed = BillingDecision.queryResultFor(responseOk = false, purchases = listOf(bundle))
        assertTrue(failed is PurchaseQueryResult.Unavailable)
    }

    @Test
    fun okQueryWithNoPurchasesIsAuthoritativeEmpty() {
        val result = BillingDecision.queryResultFor(responseOk = true, purchases = emptyList())
        assertEquals(PurchaseQueryResult.Available(emptyList()), result)
    }

    @Test
    fun launchOutcomePurchasedIsSuccess() {
        assertEquals(
            PurchaseResult.Success(PRODUCT_ID),
            BillingDecision.launchOutcomeFor(PRODUCT_ID, PurchaseState.PURCHASED, NO_MATCH),
        )
    }

    @Test
    fun launchOutcomePendingIsAskToBuyPending() {
        assertEquals(
            PurchaseResult.Pending(PRODUCT_ID),
            BillingDecision.launchOutcomeFor(PRODUCT_ID, PurchaseState.PENDING, NO_MATCH),
        )
    }

    @Test
    fun launchOutcomeUnspecifiedIsVerificationFailed() {
        assertEquals(
            PurchaseResult.Error(PRODUCT_ID, NO_MATCH, PurchaseErrorKind.VERIFICATION_FAILED),
            BillingDecision.launchOutcomeFor(PRODUCT_ID, PurchaseState.UNSPECIFIED, NO_MATCH),
        )
    }

    @Test
    fun launchOutcomeNoMatchIsVerificationFailed() {
        assertEquals(
            PurchaseResult.Error(PRODUCT_ID, NO_MATCH, PurchaseErrorKind.VERIFICATION_FAILED),
            BillingDecision.launchOutcomeFor(PRODUCT_ID, matchedState = null, noMatchMessage = NO_MATCH),
        )
    }

    @Test
    fun playNetworkCodesMapToNetworkUnavailable() {
        listOf(
            BillingDecision.PLAY_SERVICE_DISCONNECTED,
            BillingDecision.PLAY_SERVICE_UNAVAILABLE,
            BillingDecision.PLAY_NETWORK_ERROR,
        ).forEach { code ->
            assertEquals(PurchaseErrorKind.NETWORK_UNAVAILABLE, BillingDecision.purchaseErrorKindForPlayCode(code))
        }
    }

    @Test
    fun playUnavailableCodesMapToStorefrontUnsupportedAndNotEntitled() {
        assertEquals(
            PurchaseErrorKind.STOREFRONT_UNAVAILABLE,
            BillingDecision.purchaseErrorKindForPlayCode(BillingDecision.PLAY_ITEM_UNAVAILABLE),
        )
        assertEquals(
            PurchaseErrorKind.UNSUPPORTED,
            BillingDecision.purchaseErrorKindForPlayCode(BillingDecision.PLAY_BILLING_UNAVAILABLE),
        )
        assertEquals(
            PurchaseErrorKind.UNSUPPORTED,
            BillingDecision.purchaseErrorKindForPlayCode(BillingDecision.PLAY_FEATURE_NOT_SUPPORTED),
        )
        assertEquals(
            PurchaseErrorKind.NOT_ENTITLED,
            BillingDecision.purchaseErrorKindForPlayCode(BillingDecision.PLAY_ITEM_NOT_OWNED),
        )
        assertEquals(PurchaseErrorKind.PURCHASE_FAILED, BillingDecision.purchaseErrorKindForPlayCode(6))
    }

    private companion object {
        const val PRODUCT_ID = StoreCatalog.THEME_BUNDLE
        const val NO_MATCH = "no entitled purchase"
    }
}
