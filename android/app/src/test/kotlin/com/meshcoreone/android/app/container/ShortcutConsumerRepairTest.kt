// PortedFrom: MC1Tests/Intents/EntityIdentityTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Intents/StatusQueryIntentTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Intents/SendMessageIntentTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Intents/IntentErrorLocalizationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Intents/SendAdvertIntentTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.content.Context
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.l10n.R as L10nR
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import com.meshcoreone.android.core.services.contacts.AdvertisementError
import com.meshcoreone.android.platform.shortcuts.*
import java.util.Locale
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ShortcutConsumerRepairTest : RoomProcessTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val text = AndroidShortcutText(context)
    private fun secret(value: Int) = Bytes(ByteArray(16) { value.toByte() })

    private suspend fun connected(test: TestScope): ContainerHarness {
        val h = ContainerHarness(test, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(4), "Shortcut peer") }
        h.manager.connect(h.target())
        h.eventually("shortcut repair services ready") { h.manager.connectionState == DeviceConnectionState.READY }
        return h
    }

    @Test fun actualPickerAndPublisherOmitAmbiguousDigestsIncludingUnnamedSlotsAndOtherRadios() = runTest {
        val h = connected(this)
        val publisher = ShortcutPublisher(context, text)
        try {
            val radio = assertNotNull(h.appState.currentRadioId)
            val unique = ChannelDTO(radioId = radio, index = 0u, name = "Unique", secret = secret(1))
            val duplicate = unique.copy(id = UUID.randomUUID(), index = 1u, name = "Duplicate", secret = secret(2))
            val duplicateOtherSlot = duplicate.copy(id = UUID.randomUUID(), index = 2u, name = "Duplicate two")
            val hiddenDuplicate = unique.copy(id = UUID.randomUUID(), index = 3u, name = "Named", secret = secret(3))
            val unnamed = hiddenDuplicate.copy(id = UUID.randomUUID(), index = 4u, name = "")
            val foreign = unique.copy(id = UUID.randomUUID(), radioId = RadioId(UUID.randomUUID()), name = "Foreign")
            listOf(unique, duplicate, duplicateOtherSlot, hiddenDuplicate, unnamed, foreign).forEach { store.saveChannel(it) }
            val adapter = AppShortcutRadio(h.container)
            val targets = adapter.targets(text.channelSubtitle)
            assertEquals(listOf(TargetIdentity.channelId(unique)), targets.filter { it.kind == TargetKind.CHANNEL }.map { it.id })
            assertEquals(targets.size, targets.map { it.id }.toSet().size)
            val manager = assertNotNull(context.getSystemService(ShortcutManager::class.java))
            assertTrue(manager.maxShortcutCountPerActivity >= targets.size + 3, "Publication must include every fixture recipient")
            assertTrue(publisher.publish(targets))
            val expected = ShortcutPlan.build(targets, text, manager.maxShortcutCountPerActivity)
            assertEquals(targets.map { it.id }.toSet(), expected.mapNotNull { it.targetId }.toSet())
            assertEquals(expected.map { it.id }.toSet(), manager.dynamicShortcuts.map { it.id }.toSet())
            val rejected = listOf(duplicate, hiddenDuplicate, foreign).map { TargetIdentity.channelId(it) }.toSet()
            assertTrue(manager.dynamicShortcuts.none { it.intent?.getStringExtra(ShortcutContract.EXTRA_TARGET_ID) in rejected })
            for (channel in listOf(duplicate, hiddenDuplicate)) {
                val flow = SendMessageFlow(adapter, object : ShortcutConfirmationPort {
                    override suspend fun confirm(request: ConfirmationRequest): Boolean = fail("Ambiguous target cannot confirm")
                }, text)
                assertEquals(ShortcutError.INVALID_RECIPIENT, assertFailsWith<ShortcutException> {
                    flow.execute(TargetIdentity.channelId(channel), "No ambiguous send")
                }.error)
            }
            assertTrue(store.fetchPendingSends(radio).isEmpty())
            assertTrue(store.fetchPendingSends(foreign.radioId).isEmpty())
        } finally {
            publisher.clear()
            h.container.close()
        }
    }

    @Test fun actualStatusUsesPersistedOfflineAndConnectingNameWithoutInventingLiveValues() = runTest {
        val h = ContainerHarness(this, store)
        val gate = CompletableDeferred<Unit>()
        var connecting: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            val adapter = AppShortcutRadio(h.container)
            assertEquals(text.statusDisconnectedUnknown, StatusFlow.dialog(adapter.status(), text))
            val id = UUID.randomUUID()
            h.preferences.values[PersistenceKeys.LAST_CONNECTED_DEVICE_ID] = RuntimePreferenceValue.Text(id.canonicalString())
            h.preferences.values[PersistenceKeys.LAST_CONNECTED_RADIO_ID] =
                RuntimePreferenceValue.Text(RadioId(UUID.randomUUID()).canonicalString)
            h.preferences.values[PersistenceKeys.LAST_CONNECTED_DEVICE_NAME] = RuntimePreferenceValue.Text("Saved field radio")
            h.container.connectionPort.refreshLastConnection()
            assertEquals(text.statusDisconnectedNamed("Saved field radio"), StatusFlow.dialog(adapter.status(), text))
            assertNull(adapter.status().connectedNodeName)
            assertNull(adapter.status().batteryPercent)
            h.links.configureRadio = { it.connectGate = gate }
            connecting = async { h.manager.connect(h.target(id)) }
            h.eventually("connecting with a saved name", virtualBudgetMillis = 0) {
                h.manager.connectionState == DeviceConnectionState.CONNECTING
            }
            val snapshot = adapter.status()
            assertEquals("Saved field radio", snapshot.lastConnectedName)
            assertNull(snapshot.connectedNodeName)
            assertNull(snapshot.batteryPercent)
            assertEquals(text.statusConnecting("Saved field radio"), StatusFlow.dialog(snapshot, text))
            assertNull(h.sessions.current)
        } finally {
            connecting?.cancel()
            gate.complete(Unit)
            connecting?.join()
            h.container.close()
        }
    }

    @Test fun servicesOnlyDisappearanceAfterClassificationEscalatesBothRecipientsWithoutEnqueueOrRadioChange() = runTest {
        for (channelRecipient in listOf(false, true)) {
            val h = connected(this)
            try {
                val adapter = AppShortcutRadio(h.container)
                val radio = assertNotNull(adapter.currentRadioId)
                val channel = store.fetchChannel(radio, 0u)?.copy(name = "Shortcut channel", secret = secret(6))
                    ?: ChannelDTO(radioId = radio, index = 0u, name = "Shortcut channel", secret = secret(6))
                store.saveChannel(channel)
                val contact = assertNotNull(store.fetchContact(radio, key(4)))
                val targetId = if (channelRecipient) TargetIdentity.channelId(channel) else TargetIdentity.contactId(contact)
                assertEquals(1, adapter.targets(text.channelSubtitle).count { it.id == targetId },
                    "Each recipient fixture must resolve uniquely before services disappear")
                val shown = mutableListOf<String>()
                val opened = mutableListOf<ShortcutRequest>()
                var confirmations = 0
                val executor = ShortcutRequestExecutor(adapter, object : ShortcutConfirmationPort {
                    override suspend fun confirm(request: ConfirmationRequest): Boolean {
                        confirmations++
                        assertEquals(SendRoute.HEADLESS_QUEUE, SendRouting.route(adapter.connectionState))
                        assertNotNull(h.sessions.current).tearDown()
                        assertNull(h.sessions.current)
                        assertEquals(DeviceConnectionState.READY, adapter.connectionState)
                        assertEquals(radio, adapter.currentRadioId)
                        return true
                    }
                }, text, object : ShortcutPresenter {
                    override fun show(message: String) { shown += message }
                    override fun openApp(request: ShortcutRequest) { opened += request }
                })
                val request = ShortcutRequest.Send(targetId, "Never enqueued")
                executor.run(request)
                assertEquals(1, confirmations)
                assertEquals(listOf(text.sendForeground), shown)
                assertEquals(listOf<ShortcutRequest>(request), opened)
                assertTrue(store.fetchMessages(EntityKey(radio, contact.id), 100, 0).isEmpty())
                assertTrue(store.fetchMessages(radio, channel.index, 100, 0).isEmpty())
                assertTrue(store.fetchPendingSends(radio).isEmpty())
                assertFalse(h.links.radios.single().sent.any { it[0].toInt() in 2..3 })
                assertEquals(1, h.lifecycle.created.size)
                assertEquals(radio, adapter.currentRadioId)
            } finally { h.container.close() }
        }
    }

    @Test fun actualAdvertExecutorUsesExistingAdvertisementCopyForEveryLocaleAndFallback() = runTest {
        val h = connected(this)
        try {
            val adapter = AppShortcutRadio(h.container)
            val failures = listOf(
                AdvertisementError.SendFailed(),
                AdvertisementError.InvalidResponse(),
                IllegalStateException("private diagnostic never spoken"),
            )
            for (locale in listOf("de", "en", "es", "fr", "it", "ko", "nl", "pl", "pt", "ru", "uk", "zh-Hans")) {
                val configuration = Configuration(context.resources.configuration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(locale)))
                }
                val localizedContext = context.createConfigurationContext(configuration)
                val localized = AndroidShortcutText(localizedContext)
                val expected = localizedContext.getString(L10nR.string.l10n_app_localizable_error_advertisement_sendfailed)
                assertTrue(expected.isNotBlank())
                assertFalse(expected.startsWith("error."))
                val ownKeys = listOf(
                    ShortcutError.NOT_CONNECTED to L10nR.string.l10n_app_localizable_error_intent_notconnected,
                    ShortcutError.INVALID_RECIPIENT to L10nR.string.l10n_app_localizable_error_intent_invalidrecipient,
                    ShortcutError.MESSAGE_TOO_LONG to L10nR.string.l10n_app_localizable_error_intent_messagetoolong,
                    ShortcutError.SEND_FAILED to L10nR.string.l10n_app_localizable_error_intent_sendfailed,
                    ShortcutError.ADVERT_FAILED to L10nR.string.l10n_app_localizable_error_advertisement_sendfailed,
                )
                for ((error, resource) in ownKeys) assertEquals(localizedContext.getString(resource), localized.error(error))
                val timeout = MeshCoreException.Timeout()
                val session = adapter.advertFailure(timeout)
                assertSame(timeout, assertIs<ShortcutError.SessionError>(session.error).underlying)
                assertSame(timeout, session.cause)
                assertEquals(localizedContext.getString(L10nR.string.l10n_app_localizable_error_meshcore_timeout),
                    localized.error(session.error))
                for (failure in failures) {
                    val mapped = adapter.advertFailure(failure)
                    assertSame(failure, mapped.cause)
                    assertEquals(ShortcutError.ADVERT_FAILED, mapped.error)
                    assertEquals(expected, localized.error(mapped.error))
                    for (reach in AdvertReach.entries) {
                        // Inject the real adapter's mapped failure; service/wire behavior has separate integration coverage.
                        val failingPort = object : ShortcutRadioPort by adapter {
                            override suspend fun sendAdvert(flood: Boolean) { throw mapped }
                        }
                        val shown = mutableListOf<String>()
                        val executor = ShortcutRequestExecutor(failingPort, object : ShortcutConfirmationPort {
                            override suspend fun confirm(request: ConfirmationRequest) = true
                        }, localized, object : ShortcutPresenter {
                            override fun show(message: String) { shown += message }
                            override fun openApp(request: ShortcutRequest) { fail("Advert error cannot hand off a send") }
                        })
                        executor.run(ShortcutRequest.Advert(reach))
                        assertEquals(listOf(expected), shown)
                    }
                }
            }
            assertFalse(h.links.radios.single().sent.any { it[0].toInt() == 7 })
            assertTrue(store.fetchPendingSends(assertNotNull(adapter.currentRadioId)).isEmpty())
        } finally { h.container.close() }
    }
}
