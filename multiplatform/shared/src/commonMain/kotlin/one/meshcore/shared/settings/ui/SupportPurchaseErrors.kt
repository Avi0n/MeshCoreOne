package one.meshcore.shared.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import one.meshcore.shared.billing.PurchaseErrorKind
import one.meshcore.shared.billing.PurchaseResult
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_support_error_network_unavailable
import one.meshcore.shared.resources.settings_support_error_not_entitled
import one.meshcore.shared.resources.settings_support_error_product_not_found
import one.meshcore.shared.resources.settings_support_error_purchase_failed
import one.meshcore.shared.resources.settings_support_error_storefront_unavailable
import one.meshcore.shared.resources.settings_support_error_unsupported
import one.meshcore.shared.resources.settings_support_error_verification_failed
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.stringResource

/** True only on a verified purchase, matching iOS `StoreState.purchase` returning `true`. */
fun shouldPresentPurchaseThanks(result: PurchaseResult): Boolean = result is PurchaseResult.Success

/** iOS `StoreServiceError.userFacingMessage` key for [kind]. */
fun storeErrorResource(kind: PurchaseErrorKind) = when (kind) {
    PurchaseErrorKind.PRODUCT_NOT_FOUND -> Res.string.settings_support_error_product_not_found
    PurchaseErrorKind.VERIFICATION_FAILED -> Res.string.settings_support_error_verification_failed
    PurchaseErrorKind.NOT_ENTITLED -> Res.string.settings_support_error_not_entitled
    PurchaseErrorKind.NETWORK_UNAVAILABLE -> Res.string.settings_support_error_network_unavailable
    PurchaseErrorKind.STOREFRONT_UNAVAILABLE -> Res.string.settings_support_error_storefront_unavailable
    PurchaseErrorKind.UNSUPPORTED -> Res.string.settings_support_error_unsupported
    PurchaseErrorKind.PURCHASE_FAILED -> Res.string.settings_support_error_purchase_failed
}

/** Substitutions [storeErrorResource] expects; only [PurchaseErrorKind.PURCHASE_FAILED] wraps a detail. */
fun storeErrorFormatArgs(error: PurchaseResult.Error): List<Any> =
    if (error.kind == PurchaseErrorKind.PURCHASE_FAILED) listOf(error.message) else emptyList()

@Composable
internal fun rememberPurchaseErrorText(): (PurchaseResult) -> String? {
    val templates = PurchaseErrorKind.entries.associateWith { stringResource(storeErrorResource(it)) }
    return remember(templates) {
        { result ->
            val error = result as? PurchaseResult.Error ?: return@remember null
            val template = templates[error.kind] ?: return@remember null
            formatTemplate(template, storeErrorFormatArgs(error))
        }
    }
}
