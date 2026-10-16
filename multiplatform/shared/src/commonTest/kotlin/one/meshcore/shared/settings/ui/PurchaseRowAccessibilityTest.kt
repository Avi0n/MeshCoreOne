package one.meshcore.shared.settings.ui

import one.meshcore.shared.billing.ProductDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PurchaseRowAccessibilityTest {
    private val coffee = ProductDetails(
        productId = "tip.coffee",
        title = "Coffee",
        description = "",
        formattedPrice = "$2.99",
    )

    private val text = PurchaseRowAccessibilityText(
        contributionTemplate = "%1\$s, %2\$s",
        bundleLoadingLabel = "loading",
        bundleLockedTemplate = "bundle, %s",
        contributionHint = "buy tip",
        bundleLockedHint = "buy bundle",
    )

    @Test
    fun aBlankOrProductIdTitleFallsBackToTheLocalName() {
        assertEquals("Coffee", purchaseRowTitle(null, "Coffee"))
        assertEquals("Coffee", purchaseRowTitle(coffee.copy(title = "  "), "Coffee"))
        assertEquals("Coffee", purchaseRowTitle(coffee.copy(title = "tip.coffee"), "Coffee"))
        assertEquals("Morning coffee", purchaseRowTitle(coffee.copy(title = "Morning coffee"), "Coffee"))
    }

    @Test
    fun aBlankStorePriceIsNotAPrice() {
        assertNull(purchaseRowPrice(null))
        assertNull(purchaseRowPrice(coffee.copy(formattedPrice = " ")))
        assertEquals("$2.99", purchaseRowPrice(coffee))
    }

    @Test
    fun ownedWinsThenPendingThenTheSubtitleOnlyWithoutAPrice() {
        assertEquals(
            "Owned",
            purchaseRowSupportingStatus(
                owned = true,
                ownedLabel = "Owned",
                pendingLabel = "Pending",
                fallbackSubtitle = "Subtitle",
                price = "$2.99",
            ),
        )
        assertEquals(
            "Pending",
            purchaseRowSupportingStatus(
                owned = false,
                ownedLabel = "Owned",
                pendingLabel = "Pending",
                fallbackSubtitle = "Subtitle",
                price = null,
            ),
        )
        assertEquals(
            "Subtitle",
            purchaseRowSupportingStatus(
                owned = false,
                ownedLabel = "Owned",
                pendingLabel = null,
                fallbackSubtitle = "Subtitle",
                price = null,
            ),
        )
        assertNull(
            purchaseRowSupportingStatus(
                owned = false,
                ownedLabel = "Owned",
                pendingLabel = null,
                fallbackSubtitle = "Subtitle",
                price = "$2.99",
            ),
        )
    }

    @Test
    fun aTipRowSpeaksTheNameAndPriceAndActivatesOnlyWithAPrice() {
        val ready = row(PurchaseRowA11yKind.Contribution, price = "$2.99", owned = false, enabled = true)
        assertEquals("Coffee, $2.99", ready.label)
        assertTrue(ready.enabled)
        assertEquals("buy tip", ready.hint)

        val missingPrice = row(PurchaseRowA11yKind.Contribution, price = null, owned = false, enabled = true)
        assertEquals("Coffee, ", missingPrice.label)
        assertFalse(missingPrice.enabled)

        val owned = row(PurchaseRowA11yKind.Contribution, price = "$2.99", owned = true, enabled = true)
        assertFalse(owned.enabled)
    }

    @Test
    fun aBundleRowStaysLoadingUntilItCanBeBought() {
        val loading = row(PurchaseRowA11yKind.Bundle, price = null, owned = false, enabled = true, pending = false)
        assertEquals("loading", loading.label)
        assertFalse(loading.enabled)
        assertNull(loading.hint)

        val priced = row(PurchaseRowA11yKind.Bundle, price = "$4.99", owned = false, enabled = true, pending = false)
        assertEquals("bundle, $4.99", priced.label)
        assertTrue(priced.enabled)
        assertEquals("buy bundle", priced.hint)

        val pending = row(PurchaseRowA11yKind.Bundle, price = "$4.99", owned = false, enabled = true, pending = true)
        assertFalse(pending.enabled)
        assertNull(pending.hint)
    }

    @Test
    fun thePriceButtonUsesTheStorePriceWhenItCanActivate() {
        assertTrue(purchaseRowButtonEnabled(enabled = true, price = "$2.99"))
        assertFalse(purchaseRowButtonEnabled(enabled = true, price = null))
        assertFalse(purchaseRowButtonEnabled(enabled = false, price = "$2.99"))
        assertEquals("$2.99", purchaseRowButtonLabel("$2.99", "—"))
        assertEquals("—", purchaseRowButtonLabel(null, "—"))
    }

    private fun row(
        kind: PurchaseRowA11yKind,
        price: String?,
        owned: Boolean,
        enabled: Boolean,
        pending: Boolean = false,
    ): PurchaseRowAccessibility = purchaseRowAccessibility(
        PurchaseRowAccessibilityInput(
            kind = kind,
            title = "Coffee",
            price = price,
            pending = pending,
            owned = owned,
            enabled = enabled,
            text = text,
        ),
    )
}
