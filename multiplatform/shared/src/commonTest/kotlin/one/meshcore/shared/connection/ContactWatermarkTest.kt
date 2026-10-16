package one.meshcore.shared.connection

import one.meshcore.shared.contact.VContactIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContactWatermarkTest {
    @Test
    fun noneWhenMissingOrZero() {
        val now = 1_800_000_000L
        assertEquals(ContactWatermarkUse.None, contactWatermarkUse(null, now))
        assertEquals(ContactWatermarkUse.None, contactWatermarkUse(0, now))
    }

    @Test
    fun minuteLeadIsIncrementalAndFarFutureIsInvalid() {
        val now = 1_800_000_000L
        val minuteLead = now + 5L * 60L
        val farFuture = now + 30L * 24L * 60L * 60L
        val incremental = contactWatermarkUse(minuteLead, now)
        assertIs<ContactWatermarkUse.Incremental>(incremental)
        assertEquals(minuteLead, incremental.watermark)
        val invalid = contactWatermarkUse(farFuture, now)
        assertIs<ContactWatermarkUse.Invalid>(invalid)
        assertEquals(farFuture, invalid.stored)
    }

    @Test
    fun incrementalSinceRewindsOneSecond() {
        assertEquals(99L, incrementalContactSince(100L))
        assertEquals(0L, incrementalContactSince(0L))
        assertEquals(0L, incrementalContactSince(1L))
        assertEquals(0L, PRUNE_FREE_FULL_FETCH_SINCE)
    }

    @Test
    fun unknownOrZeroMaxContactsIsNeverAtCapacity() {
        val keys = listOf(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 })
        assertFalse(contactTableCapacity(maxContacts = 0, keys, selfPublicKey = null).atCapacity)
        assertFalse(contactTableCapacity(maxContacts = -1, keys, selfPublicKey = null).atCapacity)
    }

    @Test
    fun realContactsAtTheRadioCapAreAtCapacity() {
        val keys = listOf(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 })
        val capacity = contactTableCapacity(maxContacts = 2, keys, selfPublicKey = null)
        assertTrue(capacity.atCapacity)
        assertEquals(2, capacity.realContactCount)
    }

    @Test
    fun vContactInTheLastSlotDoesNotTripCapacity() {
        val self = ByteArray(32) { 0x01 }
        val vKey = requireNotNull(VContactIdentity.publicKey(self))
        val keys = listOf(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }, vKey)
        val capacity = contactTableCapacity(maxContacts = 3, keys, selfPublicKey = self)
        assertFalse(capacity.atCapacity)
        assertEquals(2, capacity.realContactCount)
    }
}
