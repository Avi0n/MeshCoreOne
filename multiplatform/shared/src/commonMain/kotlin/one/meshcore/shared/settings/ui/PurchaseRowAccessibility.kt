package one.meshcore.shared.settings.ui

import one.meshcore.shared.billing.ProductDetails

internal data class PurchaseRowAccessibility(val label: String, val enabled: Boolean, val hint: String?)

internal data class PurchaseRowAccessibilityText(
    val contributionTemplate: String,
    val bundleLoadingLabel: String,
    val bundleLockedTemplate: String,
    val contributionHint: String,
    val bundleLockedHint: String,
)

internal data class PurchaseRowAccessibilityInput(
    val kind: PurchaseRowA11yKind,
    val title: String,
    val price: String?,
    val pending: Boolean,
    val owned: Boolean,
    val enabled: Boolean,
    val text: PurchaseRowAccessibilityText,
)

/** A blank Play title, or one that repeats the product id, falls back to the local name. */
internal fun purchaseRowTitle(details: ProductDetails?, fallbackTitle: String): String =
    details?.title?.takeIf { it.isNotBlank() && it != details.productId } ?: fallbackTitle

/** A blank store price is not a price yet. */
internal fun purchaseRowPrice(details: ProductDetails?): String? = details?.formattedPrice?.takeIf { it.isNotBlank() }

/**
 * Owned wins, then a pending purchase. Otherwise the subtitle shows only while the price
 * is still missing.
 */
internal fun purchaseRowSupportingStatus(
    owned: Boolean,
    ownedLabel: String,
    pendingLabel: String?,
    fallbackSubtitle: String?,
    price: String?,
): String? = when {
    owned -> ownedLabel
    pendingLabel != null -> pendingLabel
    else -> fallbackSubtitle?.takeIf { price == null }
}

/** Spoken label, whether the row can activate, and the click hint for a tip or the bundle. */
internal fun purchaseRowAccessibility(input: PurchaseRowAccessibilityInput): PurchaseRowAccessibility {
    val label = when (input.kind) {
        PurchaseRowA11yKind.Contribution ->
            contributionRowAccessibilityLabel(
                displayName = input.title,
                displayPrice = input.price ?: "",
                template = input.text.contributionTemplate,
            )
        PurchaseRowA11yKind.Bundle ->
            bundleCardAccessibilityLabel(
                pending = input.pending,
                displayPrice = input.price,
                loadingLabel = input.text.bundleLoadingLabel,
                lockedTemplate = input.text.bundleLockedTemplate,
            )
    }
    val rowEnabled = when (input.kind) {
        PurchaseRowA11yKind.Contribution -> input.enabled && input.price != null && !input.owned
        PurchaseRowA11yKind.Bundle ->
            input.enabled && bundleCardIsActionable(input.pending, input.price) && !input.owned
    }
    val hint = when (input.kind) {
        PurchaseRowA11yKind.Contribution -> input.text.contributionHint
        PurchaseRowA11yKind.Bundle ->
            if (bundleCardIsActionable(input.pending, input.price)) input.text.bundleLockedHint else null
    }
    return PurchaseRowAccessibility(label = label, enabled = rowEnabled, hint = hint)
}

/** The price button activates only when a price is showing. */
internal fun purchaseRowButtonEnabled(enabled: Boolean, price: String?): Boolean = enabled && price != null

/** The button shows the store price, or the unavailable mark when the price has not arrived. */
internal fun purchaseRowButtonLabel(price: String?, unavailable: String): String = price ?: unavailable
