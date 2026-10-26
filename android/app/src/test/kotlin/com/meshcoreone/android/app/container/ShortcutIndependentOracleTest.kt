// PortedFrom: MC1Tests/Intents/StatusQueryIntentTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Intents/SendMessageIntentTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Intents/SendAdvertIntentTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Intents/IntentErrorLocalizationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.services.contacts.AdvertisementError
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.platform.shortcuts.*
import java.util.Locale
import kotlin.test.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ShortcutIndependentOracleTest : RoomProcessTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>().let { app ->
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) })
    }
    private val text = AndroidShortcutText(context)

    private suspend fun connected(test: TestScope): ContainerHarness {
        val h = ContainerHarness(test, store)
        h.links.configureRadio = { firmware ->
            firmware.knownContacts += contactFrame(key(4), "Independent peer")
            firmware.respond = { frame -> if (frame[0].toInt() in 2..3) emptyList() else null }
        }
        h.manager.connect(h.target())
        h.eventually("independent shortcut oracle READY") { h.manager.connectionState == DeviceConnectionState.READY }
        h.settle()
        return h
    }

    private fun executor(
        radio: ShortcutRadioPort,
        shown: MutableList<String>,
        onConfirm: suspend (ConfirmationRequest) -> Boolean = { true },
    ) = ShortcutRequestExecutor(radio, object : ShortcutConfirmationPort {
        override suspend fun confirm(request: ConfirmationRequest) = onConfirm(request)
    }, text, object : ShortcutPresenter {
        override fun show(message: String) { shown += message }
        override fun openApp(request: ShortcutRequest) { fail("This oracle expects a terminal dialog") }
    })

    @Test fun actualStatusConvertsCustomElevenPoint4100MillivoltsToLiteral93PercentAndZeroToNoReading() = runTest {
        val h = connected(this)
        try {
            val device = assertNotNull(h.appState.connectedDevice)
            h.manager.replaceConnectedDevice(device.copy(
                nodeName = "Base Camp", ocvPreset = OCVPreset.CUSTOM.rawValue,
                customOCVArrayString = "4200,4060,3980,3920,3870,3820,3790,3770,3730,3680,3000",
            ))
            h.appState.batteryMonitor.stop()
            h.appState.batteryMonitor.deviceBattery = BatteryInfo(level = 4100)
            val adapter = AppShortcutRadio(h.container)
            assertEquals(93, adapter.status().batteryPercent)
            val shown = mutableListOf<String>()
            executor(adapter, shown).run(ShortcutRequest.Status)
            assertEquals(listOf("Base Camp is connected. Battery is about 93%, as of the last reading."), shown)
            assertFalse(shown.single().contains("4100"))
            // Original rungs are pure cached-state parameters, not simulated runtime connection transitions.
            for (state in listOf(DeviceConnectionState.READY, DeviceConnectionState.SYNCING, DeviceConnectionState.CONNECTED)) {
                val cached = adapter.status().copy(connectionState = state)
                assertEquals("Base Camp is connected. Battery is about 93%, as of the last reading.",
                    StatusFlow.dialog(cached, text))
            }
            for (reading in listOf(BatteryInfo(level = 0), null)) {
                h.appState.batteryMonitor.deviceBattery = reading
                assertNull(adapter.status().batteryPercent)
                shown.clear()
                executor(adapter, shown).run(ShortcutRequest.Status)
                assertEquals(listOf("Base Camp is connected. No battery reading is available."), shown)
                assertFalse(shown.single().contains("0%"))
            }
        } finally { h.container.close() }
    }

    @Test fun actualChannelAdapterAcceptsExactUtf8BudgetRejectsOneMoreAndRechecksChangedLiveName() = runTest {
        val h = connected(this)
        try {
            val adapter = AppShortcutRadio(h.container)
            val radio = assertNotNull(adapter.currentRadioId)
            val channel = ChannelDTO(radioId = radio, index = 0u, name = "Oracle", secret = Bytes(ByteArray(16) { 9 }))
            store.saveChannel(channel)
            val device = assertNotNull(h.appState.connectedDevice)
            h.manager.replaceConnectedDevice(device.copy(nodeName = ""))
            adapter.queueMessage(ShortcutRecipient.Channel(channel), "x".repeat(137))
            assertEquals("x".repeat(137), store.fetchMessages(radio, 0u, 100, 0).single().text)
            assertEquals(ShortcutError.MESSAGE_TOO_LONG, assertFailsWith<ShortcutException> {
                adapter.queueMessage(ShortcutRecipient.Channel(channel), "x".repeat(138))
            }.error)
            val flow = SendMessageFlow(adapter, object : ShortcutConfirmationPort {
                override suspend fun confirm(request: ConfirmationRequest): Boolean {
                    h.manager.replaceConnectedDevice(device.copy(nodeName = "\u8282\u70b9"))
                    return true
                }
            }, text)
            assertEquals(ShortcutError.MESSAGE_TOO_LONG, assertFailsWith<ShortcutException> {
                flow.execute(TargetIdentity.channelId(channel), "x".repeat(137))
            }.error)
            assertEquals(6, adapter.connectedNodeNameByteCount)
            val exact = "\u6d4b".repeat(43) + "ab"
            assertEquals(131, exact.toByteArray(Charsets.UTF_8).size)
            adapter.queueMessage(ShortcutRecipient.Channel(channel), exact)
            val messages = store.fetchMessages(radio, 0u, 100, 0)
            assertEquals(setOf("x".repeat(137), exact), messages.map { it.text }.toSet())
            assertEquals(2, store.fetchPendingSends(radio).size)
        } finally { h.container.close() }
    }

    @Test fun actualConversationObserversRefreshForDurableSendButNotServicesOnlyForeground() = runTest {
        val h = connected(this)
        val observed = mutableListOf<Int>()
        val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.appState.conversationsVersion.collect { observed += it }
        }
        try {
            val adapter = AppShortcutRadio(h.container)
            val radio = assertNotNull(adapter.currentRadioId)
            val contact = assertNotNull(store.fetchContact(radio, key(4)))
            val id = TargetIdentity.contactId(contact)
            val before = h.appState.conversationsVersion.value
            val flow = SendMessageFlow(adapter, object : ShortcutConfirmationPort {
                override suspend fun confirm(request: ConfirmationRequest) = true
            }, text)
            assertEquals(SendResult.Queued, flow.execute(id, "durable original"))
            assertEquals(before + 1, h.appState.conversationsVersion.value)
            h.eventually("real conversations observer receives send refresh") { observed.lastOrNull() == before + 1 }
            val stored = store.fetchMessages(EntityKey(radio, contact.id), 100, 0).single()
            assertEquals("durable original", stored.text)
            assertEquals(1, store.fetchPendingSendsForMessage(EntityKey(radio, stored.id)).size)
            assertNotNull(h.sessions.current).tearDown()
            h.settle()
            assertEquals(DeviceConnectionState.READY, adapter.connectionState)
            assertEquals(radio, adapter.currentRadioId)
            val beforeForeground = h.appState.conversationsVersion.value
            val observedCount = observed.size
            assertEquals(SendResult.NeedsForeground, flow.execute(id, "must not persist"))
            h.settle()
            assertEquals(beforeForeground, h.appState.conversationsVersion.value)
            assertEquals(observedCount, observed.size)
            assertEquals(listOf("durable original"), store.fetchMessages(EntityKey(radio, contact.id), 100, 0).map { it.text })
            assertEquals(1, store.fetchPendingSends(radio).size)
        } finally {
            observer.cancel()
            h.container.close()
        }
    }

    @Test fun actualServiceErrorBucketsPreserveTypedPayloadAndPresentIndependentLiteralCopy() = runTest {
        val h = connected(this)
        try {
            val adapter = AppShortcutRadio(h.container)
            val timeout = MeshCoreException.Timeout()
            val notConnected = "No radio is connected. Connect a radio in MeshCore One, then try again."
            val invalid = "Choose one recipient, either a contact or a channel that can receive messages."
            val tooLong = "The message is too long to send."
            val failed = "The message couldn't be queued to send. Try again."
            val timedOut = "The operation timed out. Please try again."
            val cases = listOf(
                MessageServiceException(MessageServiceError.NotConnected) to (ShortcutError.NOT_CONNECTED to notConnected),
                MessageServiceException(MessageServiceError.ContactNotFound) to (ShortcutError.INVALID_RECIPIENT to invalid),
                MessageServiceException(MessageServiceError.ChannelNotFound) to (ShortcutError.INVALID_RECIPIENT to invalid),
                MessageServiceException(MessageServiceError.InvalidRecipient) to (ShortcutError.INVALID_RECIPIENT to invalid),
                MessageServiceException(MessageServiceError.MessageTooLong) to (ShortcutError.MESSAGE_TOO_LONG to tooLong),
                MessageServiceException(MessageServiceError.SendFailed("private IO")) to (ShortcutError.SEND_FAILED to failed),
                MessageServiceException(MessageServiceError.SessionError(timeout)) to (ShortcutError.SessionError(timeout) to timedOut),
                ChannelServiceError.NotConnected() to (ShortcutError.NOT_CONNECTED to notConnected),
                ChannelServiceError.ChannelNotFound() to (ShortcutError.INVALID_RECIPIENT to invalid),
                ChannelServiceError.InvalidChannelIndex() to (ShortcutError.INVALID_RECIPIENT to invalid),
                ChannelServiceError.SecretHashingFailed() to (ShortcutError.SEND_FAILED to failed),
                ChannelServiceError.SaveFailed("private IO") to (ShortcutError.SEND_FAILED to failed),
                ChannelServiceError.SendFailed("private IO") to (ShortcutError.SEND_FAILED to failed),
                ChannelServiceError.SyncAlreadyInProgress() to (ShortcutError.SEND_FAILED to failed),
                ChannelServiceError.CircuitBreakerOpen(3) to (ShortcutError.SEND_FAILED to failed),
                ChannelServiceError.SessionError(timeout) to (ShortcutError.SessionError(timeout) to timedOut),
                ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected) to (ShortcutError.NOT_CONNECTED to notConnected),
                ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(timeout)) to (ShortcutError.SEND_FAILED to failed),
                IllegalStateException("private unmapped fault") to (ShortcutError.SEND_FAILED to failed),
            )
            val radio = assertNotNull(adapter.currentRadioId)
            val contact = assertNotNull(store.fetchContact(radio, key(4)))
            for ((failure, expected) in cases) {
                val mapped = adapter.sendFailure(failure)
                assertEquals(expected.first, mapped.error)
                assertSame(failure, mapped.cause)
                when (val error = mapped.error) {
                    is ShortcutError.SessionError -> assertSame(timeout, error.underlying)
                    else -> Unit
                }
                val failing = object : ShortcutRadioPort by adapter {
                    override suspend fun queueMessage(recipient: ShortcutRecipient, text: String) { throw mapped }
                }
                val shown = mutableListOf<String>()
                val version = h.appState.conversationsVersion.value
                executor(failing, shown).run(ShortcutRequest.Send(TargetIdentity.contactId(contact), "original"))
                assertEquals(listOf(expected.second), shown)
                assertEquals(version, h.appState.conversationsVersion.value)
                assertFalse(shown.single().contains("private"))
            }
            assertTrue(store.fetchMessages(EntityKey(radio, contact.id), 100, 0).isEmpty())
            assertTrue(store.fetchPendingSends(radio).isEmpty())
        } finally { h.container.close() }
    }

    @Test fun actualPendingFailureRecoveryCannotDowngradeDeliveredRows() = runTest {
        val h = connected(this)
        try {
            val live = assertNotNull(h.sessions.current)
            val radio = live.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(4)))
            val pending = live.messageService.createPendingMessage("pending original", contact, TextType.PLAIN, null)
            val delivered = live.messageService.createPendingMessage("delivered original", contact, TextType.PLAIN, null)
            val pendingKey = EntityKey(radio, pending.id)
            val deliveredKey = EntityKey(radio, delivered.id)
            assertEquals(MessageStatus.PENDING, assertNotNull(store.fetchMessage(pendingKey)).status)
            store.updateMessageAck(deliveredKey, 0x12345678u, MessageStatus.DELIVERED)
            h.container.chatsFeature.failPending(pending)
            h.container.chatsFeature.failPending(delivered)
            assertEquals(MessageStatus.FAILED, assertNotNull(store.fetchMessage(pendingKey)).status)
            val spared = assertNotNull(store.fetchMessage(deliveredKey))
            assertEquals(MessageStatus.DELIVERED, spared.status)
            assertEquals("delivered original", spared.text)
        } finally { h.container.close() }
    }

    @Test fun actualAdvertPresenterUsesLiteralHonestReachCopyAndPassesExistingTooLongThrough() = runTest {
        val h = connected(this)
        try {
            val adapter = AppShortcutRadio(h.container)
            val firmware = h.links.radios.single()
            val original = firmware.respond
            val sentBefore = firmware.sent.size
            firmware.respond = { frame -> if (frame[0].toInt() == 7) listOf(Bytes.of(0)) else original(frame) }
            for ((reach, expected) in listOf(
                AdvertReach.ZERO_HOP to "Sent your advert to nearby nodes.",
                AdvertReach.FLOOD to "Sent your advert across the mesh.",
            )) {
                val shown = mutableListOf<String>()
                executor(adapter, shown).run(ShortcutRequest.Advert(reach))
                assertEquals(listOf(expected), shown)
                assertFalse(shown.single().lowercase(Locale.ROOT).contains("delivered"))
            }
            val tooLong = ShortcutException(ShortcutError.MESSAGE_TOO_LONG)
            assertSame(tooLong, adapter.advertFailure(tooLong))
            val timeout = MeshCoreException.Timeout()
            val failures = listOf(
                AdvertisementError.NotConnected() to "No radio is connected. Connect a radio in MeshCore One, then try again.",
                AdvertisementError.SendFailed() to "Failed to send advertisement.",
                AdvertisementError.InvalidResponse() to "Failed to send advertisement.",
                timeout to "The operation timed out. Please try again.",
                AdvertisementError.SessionError(timeout) to "The operation timed out. Please try again.",
                IllegalStateException("private raw diagnostic") to "Failed to send advertisement.",
                tooLong to "The message is too long to send.",
            )
            for ((failure, expected) in failures) {
                val mapped = adapter.advertFailure(failure)
                if (failure === tooLong) assertSame(tooLong, mapped)
                else assertSame(failure, mapped.cause)
                if (failure === timeout || failure is AdvertisementError.SessionError) {
                    assertSame(timeout, assertIs<ShortcutError.SessionError>(mapped.error).underlying)
                }
                val failing = object : ShortcutRadioPort by adapter {
                    override suspend fun sendAdvert(flood: Boolean) { throw mapped }
                }
                for (reach in AdvertReach.entries) {
                    val shown = mutableListOf<String>()
                    executor(failing, shown).run(ShortcutRequest.Advert(reach))
                    assertEquals(listOf(expected), shown)
                    assertFalse(shown.single().contains("private"))
                }
            }
            assertEquals(listOf(Bytes.of(7), Bytes.of(7, 1)), firmware.sent.drop(sentBefore).filter { it[0].toInt() == 7 })
        } finally { h.container.close() }
    }
}
