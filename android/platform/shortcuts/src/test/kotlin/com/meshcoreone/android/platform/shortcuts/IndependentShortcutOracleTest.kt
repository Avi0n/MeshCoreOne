// PortedFrom: MC1Tests/Intents/EntityIdentityTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Intents/IntentBridgeTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Intents/SendMessageIntentTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.platform.shortcuts

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IndependentShortcutOracleTest {
    @Test
    @SourceCases("EntityIdentityTests::contact ID derives from public key not row ID",
        "EntityIdentityTests::channel ID derives from secret digest not raw secret or index or row ID",
        "EntityIdentityTests::contact and channel I ds are distinct even when key matches")
    fun exactIdsMatchIndependentPinnedByteVectors() {
        val expectedContact = "11111111-1111-1111-1111-111111111111/contact/" + "cd".repeat(32)
        val expectedChannel = "11111111-1111-1111-1111-111111111111/channel/bb9c0f7f7e42dd3f369603d8feed4d25"
        val first = contact().copy(publicKey = Bytes(ByteArray(32) { 0xcd.toByte() }))
        assertEquals(expectedContact, TargetIdentity.contactId(first))
        assertEquals(expectedContact, TargetIdentity.contactId(first.copy(id = UUID.randomUUID())))
        val channel = channel(secret = 0x5a)
        assertEquals(expectedChannel, TargetIdentity.channelId(channel))
        assertEquals(expectedChannel, TargetIdentity.channelId(channel.copy(id = UUID.randomUUID(), index = 7u, name = "Moved")))
        assertFalse(expectedChannel.contains("5a".repeat(16)))
        val sameKey = "ab".repeat(16)
        assertEquals("11111111-1111-1111-1111-111111111111/contact/$sameKey",
            TargetIdentity.format(RADIO_A, TargetKind.CONTACT, sameKey))
        assertEquals("11111111-1111-1111-1111-111111111111/channel/$sameKey",
            TargetIdentity.format(RADIO_A, TargetKind.CHANNEL, sameKey))
        assertNotEquals(TargetIdentity.format(RADIO_A, TargetKind.CONTACT, sameKey),
            TargetIdentity.format(RADIO_A, TargetKind.CHANNEL, sameKey))
        val nonuniformRadio = RadioId(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"))
        assertEquals("01234567-89AB-CDEF-0123-456789ABCDEF/channel/0b5315d9af65da09a676a559b4614da6",
            TargetIdentity.channelId(channel.copy(radioId = nonuniformRadio, secret = Bytes(ByteArray(16) { it.toByte() }))))
    }

    @Test
    @SourceCases("EntityIdentityTests::distinct channel secrets do not collide")
    fun allEightSecretsMatchIndependentDomainSeparatedSha256Vectors() {
        // Python hashlib over pinned Swift domain UTF-8 + RFC UUID bytes + 16 repeated secret bytes.
        val digests = listOf(
            "0ca043703391a2423948b4d742d8e5d2", "4fcd9313b3883448a4ccda8353b8c7a0",
            "472cc9941dd4aab1b2743fbe846ffd4d", "456847eb668f827e33491298fe7904f3",
            "c4c93a22f1935f4c403a0d365a1751bd", "89a1c431165d524242837c4b31b12d82",
            "466a8b9c137506e099344c9ec769b694", "6266e299dc838260cc96f5f142b9deb6",
        )
        val ids = (0..7).map { TargetIdentity.channelId(channel(index = it, secret = it)) }
        assertEquals(digests.map { "11111111-1111-1111-1111-111111111111/channel/$it" }, ids)
        assertEquals(8, ids.toSet().size)
    }

    @Test
    @SourceCases("SendMessageIntentTests::channel message at limit accepted",
        "SendMessageIntentTests::overlong channel message rejected",
        "SendMessageIntentTests::channel message revalidated against live node name at send time")
    fun channelBudgetUsesExactUtf8BytesAndFullZeroNameBoundary() = runSuspend {
        val recipient = ShortcutRecipient.Channel(channel())
        SendRouting.validate("x".repeat(137), recipient, 0)
        assertEquals(ShortcutError.MESSAGE_TOO_LONG, assertFailsWith<ShortcutException> {
            SendRouting.validate("x".repeat(138), recipient, 0)
        }.error)
        val nodeName = "\u8282\u70b9"
        assertEquals(6, nodeName.toByteArray(Charsets.UTF_8).size)
        val atBudget = "\u6d4b".repeat(43) + "ab"
        assertEquals(131, atBudget.toByteArray(Charsets.UTF_8).size)
        SendRouting.validate(atBudget, recipient, 6)
        assertEquals(ShortcutError.MESSAGE_TOO_LONG, assertFailsWith<ShortcutException> {
            SendRouting.validate(atBudget + "x", recipient, 6)
        }.error)
        val radio = FakeRadio(connectedNodeNameByteCount = 0, channels = listOf(recipient.dto))
        val flow = SendMessageFlow(radio, FakeConfirmation { radio.connectedNodeNameByteCount = 6 }, FakeText())
        assertEquals(ShortcutError.MESSAGE_TOO_LONG, assertFailsWith<ShortcutException> {
            flow.execute(TargetIdentity.channelId(recipient.dto), "x".repeat(137))
        }.error)
        assertTrue(radio.queued.isEmpty())
    }

    @Test
    @SourceCases("IntentBridgeTests::adopt stores the adopted app state",
        "IntentBridgeTests::re adopt replaces the previous app state")
    fun installingAReplacementHandlerRoutesOnlyToLatestOwner() {
        val dispatcher = ShortcutDispatcher()
        val first = mutableListOf<ShortcutRequest>()
        val second = mutableListOf<ShortcutRequest>()
        val held = ShortcutRequest.Send(null, "retained original")
        assertEquals(ShortcutDispatcher.Outcome.Held, dispatcher.submit(held))
        dispatcher.install { first += it }
        assertSame(held, first.single())
        dispatcher.install { second += it }
        val current = ShortcutRequest.Advert(AdvertReach.ZERO_HOP)
        assertEquals(ShortcutDispatcher.Outcome.Delivered, dispatcher.submit(current))
        assertEquals(listOf<ShortcutRequest>(held), first)
        assertSame(current, second.single())
        dispatcher.uninstall()
        assertEquals(ShortcutDispatcher.Outcome.Held, dispatcher.submit(ShortcutRequest.Status))
        assertEquals(listOf<ShortcutRequest>(current), second)
    }

    @Test
    @SourceCases("SendMessageIntentTests::connected without services never enqueues")
    fun connectedWithoutServicesDoesNotReadRecipientsOrAskForConfirmation() = runSuspend {
        val radio = FakeRadio(connectionState = DeviceConnectionState.CONNECTED)
        val guarded = object : ShortcutRadioPort by radio {
            override suspend fun contacts(radioId: RadioId): List<ContactDTO> =
                error("Foreground classification must precede recipient access")
        }
        val flow = SendMessageFlow(guarded, object : ShortcutConfirmationPort {
            override suspend fun confirm(request: ConfirmationRequest): Boolean = error("No send authorization yet")
        }, FakeText())
        assertEquals(SendResult.NeedsForeground, flow.execute("not resolved", "not queued"))
        assertTrue(radio.queued.isEmpty())
    }
}
