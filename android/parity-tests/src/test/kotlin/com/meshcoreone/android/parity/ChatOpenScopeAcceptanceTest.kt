// AndroidOnly: WP-501 Mounted channel-open scope application through the production App and real protocol session.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.FirmwareRadio
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.feature.chats.timeline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatOpenScopeAcceptanceTest : RoomProcessTest() {
    @Test fun mountedOpenPopulatesThenSetsV12AllRegionsBeforeClearingRealUnread() = runTest {
        withRadio { h, firmware, radio ->
            val channel = channel(radio).withFloodScope(ChannelFloodScope.AllRegions)
            store.saveChannel(channel)
            val viewModel = viewModel(h, channel)
            firmware.respond = { if (it[0] == 0x36.toUByte()) emptyList() else null }
            val opened = backgroundScope.async { viewModel.open() }
            h.eventually("real unscoped command") { scopes(firmware).isNotEmpty() }
            assertEquals(listOf(UNSCOPED), scopes(firmware))
            assertTrue(viewModel.state.value.hasLoadedOnce)
            assertFalse(viewModel.state.value.isLoading)
            assertEquals(9L, store.fetchChannel(radio, channel.index)?.unreadCount)
            assertFalse(opened.isCompleted)
            firmware.push(Bytes.of(0))
            opened.await()
            assertEquals(0L, store.fetchChannel(radio, channel.index)?.unreadCount)
            viewModel.open()
            assertEquals(listOf(UNSCOPED), scopes(firmware), "Only actual successful session state is deduplicated")
        }
    }

    @Test fun storedSelectionFirmwareDefaultAndOtherConversationInvalidateActualSessionScope() = runTest {
        withRadio { h, firmware, radio ->
            val channel = channel(radio).withFloodScope(ChannelFloodScope.AllRegions)
            store.saveChannel(channel)
            val first = viewModel(h, channel)
            first.open()
            val device = assertNotNull(h.manager.connectedDevice)
            h.manager.replaceConnectedDevice(device.copy(firmwareVersion = 11u))
            first.open()
            assertEquals(listOf(UNSCOPED, DISABLED), scopes(firmware))

            h.manager.replaceConnectedDevice(device.copy(defaultFloodScopeName = "test"))
            store.saveChannel(channel.withFloodScope(ChannelFloodScope.Inherit))
            first.open()
            assertEquals(REGION_TEST, scopes(firmware).last(), "Read the saved channel, not a stale route DTO")
            store.saveChannel(channel.withFloodScope(ChannelFloodScope.Region("test")))
            first.open()
            assertEquals(REGION_TEST, scopes(firmware).last())
            val beforeOther = scopes(firmware).size
            val other = channel.copy(id = java.util.UUID.randomUUID(), index = 1u)
                .withFloodScope(ChannelFloodScope.AllRegions)
            store.saveChannel(other)
            viewModel(h, other).open()
            first.open()
            assertEquals(listOf(UNSCOPED, REGION_TEST), scopes(firmware).drop(beforeOther),
                "Returning to an existing timeline must not reuse scope pushed before another channel opened")
            h.manager.replaceConnectedDevice(device.copy(defaultFloodScopeName = null))
            store.saveChannel(channel.withFloodScope(ChannelFloodScope.Inherit))
            first.open()
            assertEquals(DISABLED, scopes(firmware).last())
        }
    }

    @Test fun failedPopulationStillSetsScopeButRetainsUnreadAndTheTypedLoadError() = runTest {
        withRadio { h, firmware, radio ->
            val channel = channel(radio).withFloodScope(ChannelFloodScope.AllRegions)
            store.saveChannel(channel)
            val native = h.container.chatsFeature.timeline(TimelineConversation.Channel(channel))
            val failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("Injected population read"))
            val dependencies = object : ChatTimelineDependencies by native {
                override val data = object : TimelineDataSource by native.data {
                    override suspend fun fetchMessages(conversation: TimelineConversation, limit: Int, offset: Int): List<MessageDTO> =
                        throw failure
                }
            }
            val viewModel = ChatViewModel(TimelineConversation.Channel(channel), dependencies, backgroundScope)
            viewModel.open()
            assertSame(failure, viewModel.state.value.loadError)
            assertEquals(listOf(UNSCOPED), scopes(firmware))
            assertEquals(9L, store.fetchChannel(radio, channel.index)?.unreadCount)
        }
    }

    @Test fun realDeviceErrorIsReportedAndNeverMemoizedAsSuccessful() = runTest {
        withRadio { h, firmware, radio ->
            val channel = channel(radio).withFloodScope(ChannelFloodScope.AllRegions)
            store.saveChannel(channel)
            val native = h.container.chatsFeature.timeline(TimelineConversation.Channel(channel))
            val failures = mutableListOf<Pair<String, Throwable>>()
            val dependencies = object : ChatTimelineDependencies by native {
                override val diagnostics = TimelineDiagnostics { operation, failure -> failures += operation to failure }
            }
            val viewModel = ChatViewModel(TimelineConversation.Channel(channel), dependencies, backgroundScope)
            firmware.respond = { if (it[0] == 0x36.toUByte()) listOf(Bytes.of(1, 2)) else null }
            viewModel.open()
            assertEquals("set channel flood scope", failures.single().first)
            assertEquals(2.toUByte(), assertIs<MeshCoreException.DeviceError>(failures.single().second).code)
            firmware.respond = { null }
            viewModel.open()
            viewModel.open()
            assertEquals(listOf(UNSCOPED, UNSCOPED), scopes(firmware))
        }
    }

    @Test fun cancelledHeldOpenDoesNotClearUnreadAndANewSessionCannotInheritItsDedup() = runTest {
        withRadio { h, firmware, radio ->
            val channel = channel(radio).withFloodScope(ChannelFloodScope.AllRegions)
            store.saveChannel(channel)
            val viewModel = viewModel(h, channel)
            firmware.respond = { if (it[0] == 0x36.toUByte()) emptyList() else null }
            val opened = backgroundScope.async { viewModel.open() }
            h.eventually("held scope") { scopes(firmware).size == 1 }
            opened.cancelAndJoin()
            assertTrue(opened.isCancelled)
            assertEquals(9L, store.fetchChannel(radio, channel.index)?.unreadCount)
            val previous = assertNotNull(h.sessions.current).token
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.manager.connect(h.target())
            h.eventually("replacement ready") { h.manager.connectionState == DeviceConnectionState.READY }
            val replacement = h.links.radios.last()
            assertNotEquals(previous, assertNotNull(h.sessions.current).token)
            h.manager.replaceConnectedDevice(assertNotNull(h.manager.connectedDevice).copy(firmwareVersion = 12u))
            store.saveChannel(channel)
            viewModel.open()
            assertEquals(listOf(UNSCOPED), scopes(replacement))
        }
    }

    @Test fun foreignRadioTimelineAndDirectOpenCannotSetCurrentSessionScope() = runTest {
        withRadio { h, firmware, radio ->
            val other = RadioId(java.util.UUID.randomUUID())
            store.saveDevice(DeviceDTO(radioId = other, publicKey = Bytes(ByteArray(32) { 8 }), nodeName = "Other"))
            val foreign = channel(other).withFloodScope(ChannelFloodScope.AllRegions)
            store.saveChannel(foreign)
            viewModel(h, foreign).open()
            val contact = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 3 }), name = "Peer", lastHeardTimestamp = null)
            store.saveContact(contact)
            val direct = TimelineConversation.Direct(contact)
            ChatViewModel(direct, h.container.chatsFeature.timeline(direct), backgroundScope).open()
            assertTrue(scopes(firmware).isEmpty())
        }
    }

    private suspend fun TestScope.withRadio(body: suspend (ContainerHarness, FirmwareRadio, RadioId) -> Unit) {
        val h = ContainerHarness(this, store)
        try {
            h.links.configureRadio = { it.firmwareVersion = 12u }
            h.manager.connect(h.target())
            h.eventually("ready") { h.manager.connectionState == DeviceConnectionState.READY }
            assertEquals(12.toUByte(), assertNotNull(h.manager.connectedDevice).firmwareVersion)
            body(h, h.links.radios.single(), assertNotNull(h.sessions.current).token.radioId)
        } finally {
            withContext(NonCancellable) { h.container.close() }
        }
    }

    private fun TestScope.viewModel(h: ContainerHarness, channel: ChannelDTO): ChatViewModel {
        val conversation = TimelineConversation.Channel(channel)
        return ChatViewModel(conversation, h.container.chatsFeature.timeline(conversation), backgroundScope)
    }
    private fun channel(radio: RadioId) = ChannelDTO(radioId = radio, index = 0u, name = "Test", unreadCount = 9)
    private fun scopes(firmware: FirmwareRadio) = firmware.sent.filter { it[0] == 0x36.toUByte() }
    private companion object {
        val UNSCOPED = Bytes.fromHex("3601")
        val DISABLED = Bytes.fromHex("360000000000000000000000000000000000")
        val REGION_TEST = Bytes.fromHex("36009cd8fcf22a47333b591d96a2b848b73f")
    }
}
