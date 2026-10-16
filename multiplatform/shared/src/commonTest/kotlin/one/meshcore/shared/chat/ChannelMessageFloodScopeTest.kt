package one.meshcore.shared.chat

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.channel.FloodScope
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.simulator.MockDataProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChannelMessageFloodScopeTest {
    @Test
    fun `all send paths use the current saved region`() = runTest {
        for (path in SendPath.entries) {
            val store = InMemoryMessageStore()
            val session = FakeChatSession()
            val service = testMessageService(session, store, backgroundScope)
            val channel = ChatFixtures.channel("Region").copy(
                floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_SPECIFIC,
                regionScope = "Alpha",
            )
            store.seedChannel(channel)
            val pending = service.createPendingChannelMessage("region-test", channel.index, channel.radioID)
            store.seedChannel(channel.copy(regionScope = "Gamma"))

            when (path) {
                SendPath.INLINE -> service.sendChannelMessage("region-test", channel.index, channel.radioID)
                SendPath.PENDING -> service.sendPendingChannelMessage(pending.id)
                SendPath.RESEND -> service.resendChannelMessage(pending.id)
            }

            assertEquals(listOf(region("Gamma")), session.channelScopes, path.name)
            assertEquals(1, session.channelSends.size)
        }
    }

    @Test
    fun `inherit reads the current device default or resets the previous scope`() = runTest {
        for (default in listOf("Alpha", "", null)) {
            val store = InMemoryMessageStore()
            val session = FakeChatSession()
            val device = MockDataProvider.simulatorDevice(TEST_NOW_SECONDS).toDto().copy(
                radioID = ChatFixtures.RADIO,
                defaultFloodScopeName = default,
            )
            store.seedDevice(device)
            val service = testMessageService(session, store, backgroundScope)
            service.sendChannelMessage("region-test", 0, ChatFixtures.RADIO)
            val expected = if (default.isNullOrEmpty()) disabled else region(default)
            assertEquals(listOf(expected), session.channelScopes)
        }
    }

    @Test
    fun `all regions uses the unscoped override only on supported firmware`() = runTest {
        for (firmware in listOf(8, 12)) {
            val store = InMemoryMessageStore()
            val session = FakeChatSession()
            store.seedChannel(
                ChatFixtures.channel("Region").copy(
                    floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_ALL_REGIONS,
                ),
            )
            store.seedDevice(
                MockDataProvider.simulatorDevice(TEST_NOW_SECONDS).toDto().copy(
                    radioID = ChatFixtures.RADIO,
                    firmwareVersion = firmware,
                    defaultFloodScopeName = "Alpha",
                ),
            )
            testMessageService(session, store, backgroundScope).sendChannelMessage("region-test", 0, ChatFixtures.RADIO)
            assertEquals(listOf(if (firmware == 12) ResolvedFloodScope.Unscoped else disabled), session.channelScopes)
        }
    }

    @Test
    fun `old firmware skips only a disabled scope and missing device still applies it`() = runTest {
        for (hasDevice in listOf(true, false)) {
            val store = InMemoryMessageStore()
            val session = FakeChatSession()
            if (hasDevice) {
                store.seedDevice(
                    MockDataProvider.simulatorDevice(TEST_NOW_SECONDS).toDto().copy(
                        radioID = ChatFixtures.RADIO,
                        firmwareVersion = 7,
                    ),
                )
            }
            val service = testMessageService(session, store, backgroundScope)
            service.sendChannelMessage("region-test", 0, ChatFixtures.RADIO)
            assertEquals(if (hasDevice) emptyList() else listOf(disabled), session.channelScopes)
            assertEquals(1, session.channelSends.size)
        }
    }

    @Test
    fun `pool backoff resolves a changed saved region on every attempt`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession()
        val channel = ChatFixtures.channel("Region").copy(
            floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_SPECIFIC,
            regionScope = "Alpha",
        )
        store.seedChannel(channel)
        session.beforeScopedSend = {
            if (session.channelScopes.size == 1) {
                store.seedChannel(channel.copy(regionScope = "Gamma"))
                throw ChatDeviceException(FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND)
            }
        }
        val config = MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 1, baseDelaySeconds = 0.0))
        testMessageService(
            session,
            store,
            backgroundScope,
            config,
        ).sendChannelMessage("region-test", 0, ChatFixtures.RADIO)
        assertEquals(listOf(region("Alpha"), region("Gamma")), session.channelScopes)
        assertEquals(1, session.channelSends.size)
    }

    @Test
    fun `rejected scope fails the message before transmission`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession()
        session.beforeScopedSend = { throw ChatDeviceException(1) }
        val service = testMessageService(session, store, backgroundScope)
        val pending = service.createPendingChannelMessage("region-test", 0, ChatFixtures.RADIO)
        assertFailsWith<MessageServiceException.SessionError> { service.sendPendingChannelMessage(pending.id) }
        assertTrue(session.channelSends.isEmpty())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(pending.id)?.status)
    }

    @Test
    fun `old firmware still attempts an explicit region and reads only the originating radio`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession()
        val channel = ChatFixtures.channel("Region").copy(
            floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_SPECIFIC,
            regionScope = "Alpha",
        )
        store.seedChannel(channel)
        val device = MockDataProvider.simulatorDevice(TEST_NOW_SECONDS).toDto().copy(
            radioID = channel.radioID,
            firmwareVersion = 7,
        )
        store.seedDevice(device)
        val otherRadio = ChatFixtures.freshUuid()
        store.seedChannel(channel.copy(radioID = otherRadio, regionScope = "Other"))
        store.seedDevice(device.copy(radioID = otherRadio, defaultFloodScopeName = "Other", firmwareVersion = 12))
        val service = testMessageService(session, store, backgroundScope)
        val pending = service.createPendingChannelMessage("region-test", channel.index, channel.radioID)
        service.sendPendingChannelMessage(pending.id)
        assertEquals(listOf(region("Alpha")), session.channelScopes)
    }

    private fun region(name: String): ResolvedFloodScope = ResolvedFloodScope.Scope(FloodScope.Region(name))

    private val disabled: ResolvedFloodScope = ResolvedFloodScope.Scope(FloodScope.Disabled)

    private enum class SendPath { INLINE, PENDING, RESEND }
}
