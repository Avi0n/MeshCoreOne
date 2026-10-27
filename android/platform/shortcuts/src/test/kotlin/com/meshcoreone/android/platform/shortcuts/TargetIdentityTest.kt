// PortedFrom: MC1Tests/Intents/EntityIdentityTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.platform.shortcuts

import com.meshcoreone.android.core.model.canonicalString
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetIdentityTest {
    @Test
    @SourceCases("EntityIdentityTests::composite ID round trips", "EntityIdentityTests::kind raw values are pinned strings")
    fun compositeIdRoundTripsWithPinnedKinds() {
        val id = TargetIdentity.format(RADIO_A, TargetKind.CONTACT, "abcd")
        assertEquals(ParsedTargetId(RADIO_A, TargetKind.CONTACT, "abcd"), TargetIdentity.parse(id))
        assertEquals(listOf("contact", "channel"), TargetKind.entries.map { it.rawValue })
        assertTrue(id.contains("/contact/"))
        assertTrue(TargetIdentity.format(RADIO_A, TargetKind.CHANNEL, "x").contains("/channel/"))
    }

    @Test
    @SourceCases("EntityIdentityTests::malformed composite ID fails safe")
    fun malformedIdsFailSafe() {
        val uuid = RADIO_A.canonicalString
        listOf("", "/", "no-separators", "$uuid", "$uuid/contact", "$uuid/contact/", "not-a-uuid/contact/ab",
            "$uuid/unknown/ab", "$uuid//ab", "/contact/ab").forEach { assertNull(TargetIdentity.parse(it), it) }
    }

    @Test
    @SourceCases("EntityIdentityTests::contact ID derives from public key not row ID", "EntityIdentityTests::contact ID is radio scoped")
    fun contactIdFromPublicKeyAndRadioScoped() {
        val a = contact(row = UUID.randomUUID())
        val b = contact(row = UUID.randomUUID())
        assertEquals(TargetIdentity.contactId(a), TargetIdentity.contactId(b))
        assertNotEquals(TargetIdentity.contactId(a), TargetIdentity.contactId(contact(key = 2)))
        assertNotEquals(TargetIdentity.contactId(a), TargetIdentity.contactId(contact(radio = RADIO_B)))
    }

    @Test
    @SourceCases(
        "EntityIdentityTests::channel ID derives from secret digest not raw secret or index or row ID",
        "EntityIdentityTests::channel digest is radio scoped",
        "EntityIdentityTests::distinct channel secrets do not collide",
        "EntityIdentityTests::channel digest length is fixed width",
        "EntityIdentityTests::contact and channel I ds are distinct even when key matches",
    )
    fun channelIdFromDigestOnly() {
        val base = channel()
        assertEquals(TargetIdentity.channelId(base), TargetIdentity.channelId(channel(index = 5, name = "Renamed", row = UUID.randomUUID())))
        assertNotEquals(TargetIdentity.channelId(base), TargetIdentity.channelId(channel(radio = RADIO_B)))
        assertNotEquals(TargetIdentity.channelId(base), TargetIdentity.channelId(channel(secret = 8)))
        val hex = TargetIdentity.parse(TargetIdentity.channelId(base))!!.keyHex
        assertEquals(32, hex.length)
        assertFalse(TargetIdentity.channelId(base).contains("07070707"))
        assertNotEquals(TargetIdentity.contactId(contact()), TargetIdentity.channelId(base))
    }

    @Test
    @SourceCases(
        "EntityIdentityTests::channel resolve fails safe on duplicate digest",
        "EntityIdentityTests::channel resolve fails safe on zero match",
        "EntityIdentityTests::channel resolve accepts unique digest",
        "EntityIdentityTests::picker list excludes repeater and room contacts",
        "EntityIdentityTests::picker list excludes duplicate digest channels",
    )
    fun resolutionAndPickerFailSafe() {
        val one = channel(index = 0, name = "One", secret = 1)
        val dupA = channel(index = 1, name = "DupA", secret = 2)
        val dupB = channel(index = 2, name = "DupB", secret = 2)
        val id = TargetIdentity.channelId(one)
        assertEquals(one, SendRouting.resolveUniqueChannel(id, listOf(one, dupA, dupB)))
        assertNull(SendRouting.resolveUniqueChannel(TargetIdentity.channelId(dupA), listOf(one, dupA, dupB)))
        assertNull(SendRouting.resolveUniqueChannel(id, listOf(dupA)))
        val contacts = listOf(contact(key = 1, name = "bob"), contact(key = 2, name = "Rep", type = 2u), contact(key = 3, name = "Room", type = 3u), contact(key = 4, name = "Amy"))
        val targets = SendRouting.buildTargets(SendRouting.chatContacts(contacts), listOf(one, dupA, dupB), "Channel")
        assertEquals(listOf("Amy", "bob", "One"), targets.map { it.displayName })
        assertEquals(listOf(TargetKind.CONTACT, TargetKind.CONTACT, TargetKind.CHANNEL), targets.map { it.kind })
    }

    @Test
    fun unnamedChannelStillMakesNamedSameDigestAmbiguous() {
        val unique = channel(index = 0, name = "Unique", secret = 1)
        val named = channel(index = 1, name = "Ambiguous", secret = 2)
        val unnamedDuplicate = channel(index = 2, name = "", secret = 2)
        val unnamedUnique = channel(index = 3, name = "", secret = 3)
        val channels = listOf(unique, named, unnamedDuplicate, unnamedUnique)
        assertEquals(listOf(TargetIdentity.channelId(unique)),
            SendRouting.buildTargets(emptyList(), channels, "Channel").map { it.id })
        assertNull(SendRouting.resolveUniqueChannel(TargetIdentity.channelId(named), channels))
    }
}
