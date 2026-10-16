package one.meshcore.shared.contact.ui

import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.VContactIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactDetailDecisionsTest {
    @Test
    fun missingSelfKeyStillAllowsDelete() {
        assertTrue(allowDeleteContact(selfPublicKey = null, contactPublicKey = ByteArray(PUBLIC_KEY_BYTES) { 1 }))
    }

    @Test
    fun vContactCannotBeDeleted() {
        val selfKey = ByteArray(PUBLIC_KEY_BYTES) { 0x11 }
        val vContact = VContactIdentity.publicKey(selfKey)
        assertFalse(allowDeleteContact(selfKey, requireNotNull(vContact)))
    }

    @Test
    fun unrelatedContactCanBeDeleted() {
        val selfKey = ByteArray(PUBLIC_KEY_BYTES) { 0x11 }
        assertTrue(allowDeleteContact(selfKey, ByteArray(PUBLIC_KEY_BYTES) { 0x22 }))
    }

    @Test
    fun shareUnavailableKeepsItsOwnMessage() {
        assertEquals(
            "unavailable",
            shareAdvertFailureMessage(
                error = ContactServiceError.ShareContactUnavailable(),
                shareUnavailable = "unavailable",
                contactErrorText = { "mapped" },
                errorOccurred = "generic",
            ),
        )
    }

    @Test
    fun otherShareFailuresUseTheMappedText() {
        assertEquals(
            "mapped",
            shareAdvertFailureMessage(
                error = ContactServiceError.NotConnected(),
                shareUnavailable = "unavailable",
                contactErrorText = { "mapped" },
                errorOccurred = "generic",
            ),
        )
        assertEquals(
            "generic",
            shareAdvertFailureMessage(
                error = ContactServiceError.NotConnected(),
                shareUnavailable = "unavailable",
                contactErrorText = { null },
                errorOccurred = "generic",
            ),
        )
    }

    @Test
    fun blockedContactUnblocksWithoutConfirming() {
        var unblocked = false
        var confirmed = false
        blockedContactToggle(isBlocked = true, unblock = { unblocked = true }, confirm = { confirmed = true })
        assertTrue(unblocked)
        assertFalse(confirmed)
    }

    @Test
    fun openContactConfirmsBeforeBlocking() {
        var unblocked = false
        var confirmed = false
        blockedContactToggle(isBlocked = false, unblock = { unblocked = true }, confirm = { confirmed = true })
        assertFalse(unblocked)
        assertTrue(confirmed)
    }

    @Test
    fun settingAPathCountsAsBusy() {
        assertTrue(pathCommandBusy(pathBusy = false, isSettingPath = true))
        assertFalse(pathCommandBusy(pathBusy = false, isSettingPath = false))
        assertTrue(pathCommandBusy(pathBusy = true, isSettingPath = false))
    }

    @Test
    fun cancelDiscoveryNeedsAPathViewModel() {
        assertNull(cancelDiscoveryAction(pathViewModel = null))
    }

    @Test
    fun missingPathStateHasNoHopName() {
        assertNull(resolvedHopName(state = null, hash = byteArrayOf(1)))
    }

    @Test
    fun callbackWithDropsAMissingCallback() {
        assertNull(callbackWith(value = "contact", callback = null))
    }

    @Test
    fun callbackWithPassesTheLoadedValue() {
        var seen: String? = null
        callbackWith(value = "contact", callback = { seen = it })?.invoke()
        assertEquals("contact", seen)
    }
}

private const val PUBLIC_KEY_BYTES = 32
