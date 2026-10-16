package one.meshcore.shared.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.simulator.MockDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber")
class RoomChannelScopeInstrumentedTest {
    @Test
    fun sendReadsTrackCurrentScopesAndKeepRadioPartitionsSeparate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "channel-scope-${Uuid.random().value}.db"
        val db = meshDatabase(context, Dispatchers.IO, name)
        try {
            val radio = Uuid.random()
            val otherRadio = Uuid.random()
            val store = RoomChatStore(radio, db.chatDaos(), RxLogCorrelator(db.rxLogEntryDao()))
            val channel = ChannelDto(
                id = Uuid.random(), radioID = radio, index = 1, name = "Region", secret = ByteArray(16),
                isEnabled = true, unreadCount = 0,
                floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_SPECIFIC, regionScope = "Alpha",
            )
            db.channelDao().upsert(channel.toEntity())
            db.channelDao().upsert(
                channel.copy(id = Uuid.random(), radioID = otherRadio, regionScope = "Other").toEntity(),
            )
            val device = MockDataProvider.simulatorDevice(0.0).copy(
                id = Uuid.random(),
                radioID = radio,
                firmwareVersion = 12,
                defaultFloodScopeName = "Default",
            )
            db.deviceDao().upsert(device)
            db.deviceDao().upsert(
                device.copy(id = Uuid.random(), radioID = otherRadio, defaultFloodScopeName = "Other"),
            )

            assertEquals(
                ChannelFloodScope.Region("Alpha"),
                store.fetchChannelSendConfiguration(radio, 1).channel?.floodScope,
            )
            assertEquals("Default", store.fetchChannelSendConfiguration(radio, 1).device?.defaultFloodScopeName)
            assertEquals(12, store.fetchChannelSendConfiguration(radio, 1).device?.firmwareVersion)
            db.channelDao().upsert(channel.copy(regionScope = "Gamma").toEntity())
            db.deviceDao().upsert(device.copy(defaultFloodScopeName = "New default"))
            assertEquals(
                ChannelFloodScope.Region("Gamma"),
                store.fetchChannelSendConfiguration(radio, 1).channel?.floodScope,
            )
            assertEquals("New default", store.fetchChannelSendConfiguration(radio, 1).device?.defaultFloodScopeName)
            assertEquals(
                ChannelFloodScope.Region("Other"),
                store.fetchChannelSendConfiguration(otherRadio, 1).channel?.floodScope,
            )
            assertEquals("Other", store.fetchChannelSendConfiguration(otherRadio, 1).device?.defaultFloodScopeName)
            assertNull(store.fetchChannelSendConfiguration(Uuid.random(), 1).channel)
            assertNull(store.fetchChannelSendConfiguration(Uuid.random(), 1).device)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
