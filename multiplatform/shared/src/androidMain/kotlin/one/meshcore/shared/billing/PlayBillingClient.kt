package one.meshcore.shared.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import com.android.billingclient.api.BillingClient as GoogleBillingClient
import com.android.billingclient.api.ProductDetails as GoogleProductDetails

/**
 * Google Play Billing implementation of [BillingClient]. Requires an [Activity] for the purchase sheet;
 * [activityProvider] supplies the current foreground activity (null aborts launch as an error).
 *
 * One-time in-app products only (theme bundle + tips). Grant, acknowledge, consume, and refund
 * revocation all run in [EntitlementReconciler]; this client only surfaces Play state and the two
 * fulfilment calls. Fresh listener deliveries flow through [purchaseUpdates].
 */
class PlayBillingClient(
    appContext: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val activityProvider: () -> Activity?,
) : BillingClient {
    private val appContext = appContext.applicationContext
    private val connectMutex = Mutex()
    private var purchaseContinuation: CompletableDeferred<PurchaseResult>? = null
    private var pendingProductId: String? = null

    private val _purchaseUpdates = MutableSharedFlow<List<OwnedPurchase>>(extraBufferCapacity = PURCHASE_UPDATE_BUFFER)
    override val purchaseUpdates = _purchaseUpdates.asSharedFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener(::onPurchasesUpdated)

    @Suppress("DEPRECATION") // enablePendingPurchases() is the Billing 7.x API; 8.x uses PendingPurchasesParams.
    private val client: GoogleBillingClient = GoogleBillingClient.newBuilder(this.appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases()
        .build()

    private fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        // Deliver reported purchases for fulfilment, independent of any in-flight launch — this also
        // carries out-of-band Ask-to-Buy approvals arriving on an unrelated screen.
        val responseOk = billingResult.responseCode == GoogleBillingClient.BillingResponseCode.OK
        BillingDecision.deliveryFor(responseOk, purchases.orEmpty().map { it.toOwned() })
            ?.let { _purchaseUpdates.tryEmit(it) }
        val productId = pendingProductId
        val cont = purchaseContinuation
        purchaseContinuation = null
        pendingProductId = null
        if (cont == null || productId == null) return
        when (billingResult.responseCode) {
            GoogleBillingClient.BillingResponseCode.OK -> {
                val matched = purchases.orEmpty().firstOrNull { productId in it.products }
                cont.complete(
                    BillingDecision.launchOutcomeFor(
                        productId,
                        matched?.toOwned()?.state,
                        billingResult.debugMessage.ifBlank { MESSAGE_NO_PURCHASE },
                    ),
                )
            }
            GoogleBillingClient.BillingResponseCode.USER_CANCELED ->
                cont.complete(PurchaseResult.Cancelled(productId))
            else ->
                cont.complete(
                    PurchaseResult.Error(
                        productId,
                        billingResult.debugMessage.ifBlank { "Billing error ${billingResult.responseCode}" },
                        BillingDecision.purchaseErrorKindForPlayCode(billingResult.responseCode),
                    ),
                )
        }
    }

    override suspend fun queryProductDetails(productIds: List<String>): List<ProductDetails> =
        when (val result = queryProductCatalog(productIds)) {
            is ProductQueryResult.Available -> result.products
            ProductQueryResult.Unavailable -> emptyList()
        }

    override suspend fun queryProductCatalog(productIds: List<String>): ProductQueryResult {
        if (productIds.isEmpty()) return ProductQueryResult.Available(emptyList())
        ensureConnected()
        val products = productIds.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(GoogleBillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        val result = withContext(ioDispatcher) { client.queryProductDetails(params) }
        return BillingDecision.productQueryFor(
            result.billingResult.responseCode == GoogleBillingClient.BillingResponseCode.OK,
            result.productDetailsList.orEmpty().map { it.toShared() },
        )
    }

    override suspend fun launchPurchase(productId: String): PurchaseResult {
        val activity = activityProvider()
        val googleDetails = if (activity != null) {
            ensureConnected()
            queryGoogleDetails(listOf(productId)).firstOrNull()
        } else {
            null
        }
        if (activity == null || googleDetails == null) {
            val kind = if (activity == null) {
                PurchaseErrorKind.UNSUPPORTED
            } else {
                PurchaseErrorKind.PRODUCT_NOT_FOUND
            }
            val message = if (activity == null) MESSAGE_NO_ACTIVITY else MESSAGE_PRODUCT_MISSING
            return PurchaseResult.Error(productId, message, kind)
        }
        // Billing 7 one-time INAPP products need ProductDetails only (no subscription offer token).
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(googleDetails)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val deferred = CompletableDeferred<PurchaseResult>()
        purchaseContinuation = deferred
        pendingProductId = productId
        val launchResult = client.launchBillingFlow(activity, flowParams)
        if (launchResult.responseCode != GoogleBillingClient.BillingResponseCode.OK) {
            purchaseContinuation = null
            pendingProductId = null
            deferred.complete(
                PurchaseResult.Error(
                    productId,
                    launchResult.debugMessage.ifBlank { "Launch failed ${launchResult.responseCode}" },
                    BillingDecision.purchaseErrorKindForPlayCode(launchResult.responseCode),
                ),
            )
        }
        return deferred.await()
    }

    override suspend fun queryOwnedPurchases(): PurchaseQueryResult {
        ensureConnected()
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(GoogleBillingClient.ProductType.INAPP)
            .build()
        val result = withContext(ioDispatcher) { client.queryPurchasesAsync(params) }
        val responseOk = result.billingResult.responseCode == GoogleBillingClient.BillingResponseCode.OK
        return BillingDecision.queryResultFor(responseOk, result.purchasesList.map { it.toOwned() })
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        ensureConnected()
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        val result = withContext(ioDispatcher) { client.acknowledgePurchase(params) }
        return result.responseCode == GoogleBillingClient.BillingResponseCode.OK
    }

    override suspend fun consume(purchaseToken: String): Boolean {
        ensureConnected()
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
        val result = withContext(ioDispatcher) { client.consumePurchase(params) }
        return result.billingResult.responseCode == GoogleBillingClient.BillingResponseCode.OK
    }

    private suspend fun queryGoogleDetails(productIds: List<String>): List<GoogleProductDetails> {
        val products = productIds.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(GoogleBillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        val result = withContext(ioDispatcher) { client.queryProductDetails(params) }
        if (result.billingResult.responseCode != GoogleBillingClient.BillingResponseCode.OK) {
            return emptyList()
        }
        return result.productDetailsList.orEmpty()
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun ensureConnected() = connectMutex.withLock {
        if (client.isReady) return@withLock
        suspendCancellableCoroutine { cont ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onBillingServiceDisconnected() {
                        // Next call re-connects via isReady == false.
                    }
                },
            )
        }
    }

    private fun Purchase.toOwned(): OwnedPurchase = OwnedPurchase(
        productIds = products,
        purchaseToken = purchaseToken,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> PurchaseState.UNSPECIFIED
        },
        isAcknowledged = isAcknowledged,
    )

    private companion object {
        const val MESSAGE_NO_ACTIVITY = "No activity for billing sheet"
        const val MESSAGE_PRODUCT_MISSING = "Product not found in Play Catalog"
        const val MESSAGE_NO_PURCHASE = "Purchase completed without product"
        const val PURCHASE_UPDATE_BUFFER = 8
    }
}

private fun GoogleProductDetails.toShared(): ProductDetails = ProductDetails(
    productId = productId,
    title = title,
    description = description,
    formattedPrice = oneTimePurchaseOfferDetails?.formattedPrice.orEmpty(),
)
