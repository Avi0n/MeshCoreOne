// PortedFrom: MC1Tests/Intents/SendMessageIntentTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.platform.shortcuts

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SendMessageFlowTest {
    private val alice = contact()
    private val ops = channel()

    private fun fixture(
        radio: FakeRadio = FakeRadio(contacts = listOf(alice), channels = listOf(ops)),
        confirmation: FakeConfirmation = FakeConfirmation(),
    ) = Triple(radio, confirmation, SendMessageFlow(radio, confirmation, FakeText()))

    private fun error(block: () -> Unit): ShortcutError = assertFailsWith<ShortcutException>(block = block).error

    @Test
    @SourceCases(
        "SendMessageIntentTests::ready routes to headless queue",
        "SendMessageIntentTests::syncing routes to queue after sync",
        "SendMessageIntentTests::connected and connecting route to foreground",
        "SendMessageIntentTests::disconnected routes to not connected",
        "SendMessageIntentTests::restorable radio foreground escalates never throws",
        "SendMessageIntentTests::never connected surfaces not connected",
    )
    fun routingMatrix() {
        assertEquals(SendRoute.HEADLESS_QUEUE, SendRouting.route(DeviceConnectionState.READY))
        assertEquals(SendRoute.QUEUE_AFTER_SYNC, SendRouting.route(DeviceConnectionState.SYNCING))
        assertEquals(SendRoute.FOREGROUND_ESCALATE, SendRouting.route(DeviceConnectionState.CONNECTED))
        assertEquals(SendRoute.FOREGROUND_ESCALATE, SendRouting.route(DeviceConnectionState.CONNECTING))
        assertEquals(SendRoute.NOT_CONNECTED, SendRouting.route(DeviceConnectionState.DISCONNECTED))
        assertEquals(SendRoute.FOREGROUND_ESCALATE, SendRouting.disconnectedRoute(true))
        assertEquals(SendRoute.NOT_CONNECTED, SendRouting.disconnectedRoute(false))
    }

    @Test
    @SourceCases(
        "SendMessageIntentTests::repeater DM rejected", "SendMessageIntentTests::room DM rejected", "SendMessageIntentTests::chat DM accepted",
        "SendMessageIntentTests::overlong channel message rejected", "SendMessageIntentTests::channel message at limit accepted",
        "SendMessageIntentTests::channel message fitting total but exceeding node name budget rejected",
    )
    fun validation() {
        listOf<UByte>(2u, 3u).forEach {
            assertEquals(ShortcutError.INVALID_RECIPIENT, error { SendRouting.validate("hi", ShortcutRecipient.Contact(contact(type = it)), 5) })
        }
        SendRouting.validate("hi", ShortcutRecipient.Contact(alice), 5)
        val channelRecipient = ShortcutRecipient.Channel(ops)
        val budget = 137 - 5
        SendRouting.validate("a".repeat(budget), channelRecipient, 5)
        assertEquals(ShortcutError.MESSAGE_TOO_LONG, error { SendRouting.validate("a".repeat(budget + 1), channelRecipient, 5) })
        assertEquals(ShortcutError.MESSAGE_TOO_LONG, error { SendRouting.validate("a".repeat(budget), channelRecipient, 6) })
        assertEquals(ShortcutError.MESSAGE_TOO_LONG, error { SendRouting.validate("a".repeat(151), ShortcutRecipient.Contact(alice), 5) })
    }

    @Test
    @SourceCases("SendMessageIntentTests::ready DM enqueues pending send", "SendMessageIntentTests::ready channel enqueues pending send", "SendMessageIntentTests::syncing enqueues pending send")
    fun confirmedSendQueuesAfterNamingRecipient() = runSuspend {
        val (radio, confirmation, flow) = fixture()
        assertEquals(SendResult.Queued, flow.execute(TargetIdentity.contactId(alice), "hello"))
        assertEquals(SendResult.Queued, flow.execute(TargetIdentity.channelId(ops), "all"))
        assertEquals(listOf("hello", "all"), radio.queued.map { it.second })
        assertEquals("confirm:Alice", confirmation.requests[0].prompt)
        assertEquals("confirm:Ops", confirmation.requests[1].prompt)
        radio.connectionState = DeviceConnectionState.SYNCING
        assertEquals(SendResult.Queued, flow.execute(TargetIdentity.contactId(alice), "later"))
    }

    @Test
    fun declinedConfirmationNeverQueues() = runSuspend {
        val (radio, _, flow) = fixture(confirmation = FakeConfirmation(answer = false))
        assertEquals(SendResult.Declined, flow.execute(TargetIdentity.contactId(alice), "hello"))
        assertTrue(radio.queued.isEmpty())
    }

    @Test
    fun invalidAmbiguousAndStaleTargetsAreRejectedBeforeConfirmation() = runSuspend {
        val dup = channel(index = 3, name = "Dup", secret = 9)
        val (radio, confirmation, flow) = fixture(FakeRadio(contacts = listOf(alice, contact(radio = RADIO_B, key = 5)), channels = listOf(ops, dup, channel(index = 4, name = "Dup2", secret = 9))))
        for (id in listOf("garbage", TargetIdentity.contactId(contact(key = 99)), TargetIdentity.contactId(contact(radio = RADIO_B, key = 5)),
            TargetIdentity.channelId(dup), TargetIdentity.channelId(channel(secret = 55)))) {
            val failure = assertFailsWith<ShortcutException> { flow.execute(id, "x") }
            assertEquals(ShortcutError.INVALID_RECIPIENT, failure.error, id)
        }
        radio.currentRadioId = null
        assertFailsWith<ShortcutException> { flow.execute(TargetIdentity.contactId(alice), "x") }
        assertTrue(confirmation.requests.isEmpty() && radio.queued.isEmpty())
    }

    @Test
    @SourceCases("SendMessageIntentTests::recipient from another radio routes to foreground not A cross radio enqueue", "SendMessageIntentTests::connected without services never enqueues")
    fun stateChangeDuringConfirmationEscalatesWithoutQueueing() = runSuspend {
        val dropRadio = FakeRadio(contacts = listOf(alice))
        val (_, _, dropFlow) = fixture(dropRadio, FakeConfirmation { dropRadio.connectionState = DeviceConnectionState.DISCONNECTED })
        assertEquals(SendResult.NeedsForeground, dropFlow.execute(TargetIdentity.contactId(alice), "x"))
        assertTrue(dropRadio.queued.isEmpty())
        val switchRadio = FakeRadio(contacts = listOf(alice))
        val (_, _, switchFlow) = fixture(switchRadio, FakeConfirmation { switchRadio.currentRadioId = RADIO_B })
        assertEquals(SendResult.NeedsForeground, switchFlow.execute(TargetIdentity.contactId(alice), "x"))
        assertTrue(switchRadio.queued.isEmpty())
        for (state in listOf(DeviceConnectionState.CONNECTED, DeviceConnectionState.CONNECTING)) {
            val (radio, confirmation, flow) = fixture(FakeRadio(connectionState = state, contacts = listOf(alice)))
            assertEquals(SendResult.NeedsForeground, flow.execute(TargetIdentity.contactId(alice), "x"))
            assertTrue(radio.queued.isEmpty() && confirmation.requests.isEmpty())
        }
    }

    @Test
    @SourceCases("SendMessageIntentTests::services nil after classify routes to foreground not A silent enqueue")
    fun servicesDisappearWithoutChangingOperationalStateOrRadio() = runSuspend {
        for (state in listOf(DeviceConnectionState.READY, DeviceConnectionState.SYNCING)) {
            for (id in listOf(TargetIdentity.contactId(alice), TargetIdentity.channelId(ops))) {
                val radio = FakeRadio(connectionState = state, contacts = listOf(alice), channels = listOf(ops))
                val confirmation = FakeConfirmation {
                    radio.queueFailure = ShortcutServicesUnavailableException()
                }
                val (_, _, flow) = fixture(radio, confirmation)
                assertEquals(SendResult.NeedsForeground, flow.execute(id, "not queued"))
                assertEquals(state, radio.connectionState)
                assertEquals(RADIO_A, radio.currentRadioId)
                assertEquals(1, confirmation.requests.size)
                assertTrue(radio.queued.isEmpty())
            }
        }
    }

    @Test
    @SourceCases("SendMessageIntentTests::channel message revalidated against live node name at send time")
    fun nodeNameBudgetRevalidatedAfterConfirmation() = runSuspend {
        val radio = FakeRadio(contacts = listOf(alice), channels = listOf(ops))
        val (_, _, flow) = fixture(radio, FakeConfirmation { radio.connectedNodeNameByteCount = 30 })
        val failure = assertFailsWith<ShortcutException> { flow.execute(TargetIdentity.channelId(ops), "a".repeat(120)) }
        assertEquals(ShortcutError.MESSAGE_TOO_LONG, failure.error)
        assertTrue(radio.queued.isEmpty())
    }

    @Test
    fun disconnectedBehaviour() = runSuspend {
        val (radio, _, flow) = fixture(FakeRadio(connectionState = DeviceConnectionState.DISCONNECTED, contacts = listOf(alice)))
        assertEquals(ShortcutError.NOT_CONNECTED, assertFailsWith<ShortcutException> { flow.execute(TargetIdentity.contactId(alice), "x") }.error)
        radio.hasRestorableRadio = true
        assertEquals(SendResult.NeedsForeground, flow.execute(TargetIdentity.contactId(alice), "x"))
    }

    @Test
    @SourceCases(
        "SendMessageIntentTests::service errors rewrap to localized intent error",
        "SendMessageIntentTests::channel service errors bucket into intent errors",
        "SendMessageIntentTests::send write failures map to send failed not not connected",
    )
    fun portFailuresAreMappedNeverRaw() = runSuspend {
        val radio = FakeRadio(contacts = listOf(alice), queueFailure = IllegalStateException("raw"))
        val (_, _, flow) = fixture(radio)
        val failure = assertFailsWith<ShortcutException> { flow.execute(TargetIdentity.contactId(alice), "x") }
        assertEquals(ShortcutError.SEND_FAILED, failure.error)
        assertIs<IllegalStateException>(failure.cause)
        radio.queueFailure = ShortcutException(ShortcutError.NOT_CONNECTED)
        assertEquals(ShortcutError.NOT_CONNECTED, assertFailsWith<ShortcutException> { flow.execute(TargetIdentity.contactId(alice), "x") }.error)
    }
}
