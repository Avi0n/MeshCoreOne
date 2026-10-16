package one.meshcore.shared.settings.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.billing.BillingClient
import one.meshcore.shared.billing.EntitlementReconciler
import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.billing.NoOpBillingClient
import one.meshcore.shared.billing.PendingPurchase
import one.meshcore.shared.billing.ProductDetails
import one.meshcore.shared.billing.PurchaseResult
import one.meshcore.shared.billing.StoreCatalog
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.settings_support_contributions_confirm_button
import one.meshcore.shared.resources.settings_support_contributions_high_value_confirm
import one.meshcore.shared.resources.settings_support_error_network_unavailable
import one.meshcore.shared.resources.settings_support_error_products_not_loaded
import one.meshcore.shared.settings.DemoModeStore
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Support: header copy, demo mode when unlocked, theme-bundle / tip purchases, restore.
 * Three taps on the title unlock demo mode (iOS DemoModeManager parity).
 * Product titles/prices come from [BillingClient.queryProductDetails] when Play returns them.
 * [onContactSupport] opens the developer mailto (App Store / Play support contact requirement).
 */
@Composable
fun SupportSettingsScreen(
    demoMode: DemoModeStore? = null,
    billing: BillingClient = NoOpBillingClient(),
    entitlements: EntitlementStore? = null,
    reconciler: EntitlementReconciler? = null,
    onContactSupport: (() -> Unit)? = null,
    onRequestRefund: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    var showPurchaseThanks by remember { mutableStateOf(false) }
    var purchasingId by remember { mutableStateOf<String?>(null) }
    var confirmingTip by remember { mutableStateOf<ConfirmingTip?>(null) }
    var catalog by remember { mutableStateOf<Map<String, ProductDetails>>(emptyMap()) }
    var catalogFailed by remember { mutableStateOf(false) }
    var catalogLoadToken by remember { mutableIntStateOf(0) }
    var storeError by remember { mutableStateOf<String?>(null) }
    val productsNotLoaded = stringResource(Res.string.settings_support_error_products_not_loaded)
    val emptyOwned = remember { MutableStateFlow(emptySet<String>()) }
    val emptyPending = remember { MutableStateFlow<PendingPurchase?>(null) }
    val ownedProducts by supportStateOrEmpty(entitlements?.ownedProducts, emptyOwned).collectAsStateWithLifecycle()
    val pendingPurchase by supportStateOrEmpty(
        entitlements?.pendingPurchase,
        emptyPending,
    ).collectAsStateWithLifecycle()

    LaunchedEffect(billing, catalogLoadToken) {
        val ids = listOf(StoreCatalog.THEME_BUNDLE) + StoreCatalog.Tip.all
        val load = supportCatalogLoad(billing.queryProductCatalog(ids))
        catalog = load.products
        catalogFailed = load.failed
        storeError = productsNotLoaded.takeIf { load.failed } ?: storeError
    }

    var isRestoring by remember { mutableStateOf(false) }
    val purchaseErrorText = rememberPurchaseErrorText()

    LaunchedEffect(ownedProducts) {
        entitlements?.reconcilePendingPurchase()
    }

    // The purchaseUpdates -> reconciler.apply path (wired in the app container) is the single owner
    // of grant/acknowledge/consume, so success presents the thank-you sheet and does not grant here.
    val reportPurchase = { result: PurchaseResult, displayName: String ->
        entitlements?.applyPurchaseResult(result, displayName)
        applySupportPurchaseFollowUp(
            result,
            onThanks = {
                showPurchaseThanks = true
                storeError = null
            },
            onStatus = { storeError = purchaseErrorText(result) },
        )
    }

    val restoreUnavailableMessage = stringResource(Res.string.settings_support_error_network_unavailable)

    SupportSettingsContent(
        state = SupportSettingsContentState(
            demoMode = demoMode,
            catalog = catalog,
            entitlements = entitlements,
            pendingPurchase = pendingPurchase,
            ownsThemeBundle = StoreCatalog.THEME_BUNDLE in ownedProducts,
            purchaseEnabled = supportPurchaseEnabled(purchasingId, catalogFailed),
            isRestoring = isRestoring,
        ),
        actions = SupportSettingsContentActions(
            onPurchaseBundle = { displayName ->
                purchasingId = StoreCatalog.THEME_BUNDLE
                scope.launch {
                    reportPurchase(billing.launchPurchase(StoreCatalog.THEME_BUNDLE), displayName)
                    purchasingId = null
                }
            },
            onLaunchTip = { tipId, displayName ->
                purchasingId = tipId
                scope.launch {
                    reportPurchase(billing.launchPurchase(tipId), displayName)
                    purchasingId = null
                }
            },
            onConfirmHighValue = { confirmingTip = it },
            onRestore = {
                supportRestoreClick(isRestoring) {
                    isRestoring = true
                    scope.launch {
                        try {
                            val result = reconciler?.reconcile { billing.queryOwnedPurchases() }
                            applySupportRestore(
                                result,
                                onSuccess = { haptics.success() },
                                onUnavailable = { storeError = restoreUnavailableMessage },
                            )
                        } finally {
                            isRestoring = false
                        }
                    }
                }
            },
            onRequestRefund = onRequestRefund,
            onContactSupport = onContactSupport,
        ),
        modifier = modifier,
    )

    confirmingTip?.let { tip ->
        AlertDialog(
            onDismissRequest = { confirmingTip = null },
            title = {
                Text(formattedStringResource(Res.string.settings_support_contributions_high_value_confirm, tip.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingTip = null
                        purchasingId = tip.id
                        scope.launch {
                            reportPurchase(
                                billing.launchPurchase(tip.id),
                                supportStoreTitle(catalog[tip.id]?.title, tip.name),
                            )
                            purchasingId = null
                        }
                    },
                ) {
                    Text(formattedStringResource(Res.string.settings_support_contributions_confirm_button, tip.price))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingTip = null }) {
                    Text(stringResource(Res.string.localizable_common_cancel))
                }
            },
        )
    }
    if (showPurchaseThanks) {
        PurchaseThankYouSheet(onDismiss = { showPurchaseThanks = false })
    }
    storeError?.let { message ->
        ThreadErrorDialog(
            message = message,
            onDismiss = { storeError = null },
            onRetry = { catalogLoadToken += 1 },
        )
    }
}

/** A high-value tip awaiting the confirm dialog: its product id, display name, and resolved price. */
internal data class ConfirmingTip(val id: String, val name: String, val price: String)

/** Whether a Support purchase row speaks as a tip or as the All Themes bundle. */
internal enum class PurchaseRowA11yKind { Contribution, Bundle }

/** Play order history, where the user requests a refund for a one-time theme-bundle purchase. */
const val THEME_BUNDLE_REFUND_URL = "https://play.google.com/store/account/orderhistory"

/** iOS `PendingPurchaseBanner` `clock.badge.questionmark`. */
internal val SUPPORT_PENDING_BANNER_ICON get() = AppIcons.AccessTime

/** Developer support address (iOS `SupportContactSection` / App Review §1.5). */
const val SUPPORT_CONTACT_MAILTO = "mailto:info@meshcoreone.com"
