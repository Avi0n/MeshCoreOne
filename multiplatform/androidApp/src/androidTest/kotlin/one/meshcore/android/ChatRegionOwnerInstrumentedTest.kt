package one.meshcore.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.channel.ChannelInfoController
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.persistence.floodScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRegionOwnerInstrumentedTest {
    @Test
    fun aLateRegionRemovalCannotClearAReplacementRadiosDefault() = regionRemoval(sameRadio = false)

    @Test
    fun aLateRegionRemovalCannotClearANewOwnersDefaultOnTheSameRadio() = regionRemoval(sameRadio = true)

    @Test
    fun aMatchingRegionRemovalClearsOnlyTheCapturedDefault() = withFixture { fixture ->
        val data = ChatRadioNotificationData
        fixture.holdChannelCommit(ChannelFloodScope.Inherit)
        val action = fixture.launchAction { fixture.container.removeKnownRegion(ChatRegionOwnerFixture.REGION_A) }
        fixture.awaitCommitted()
        assertRemovedLocally(fixture)
        assertTrue(fixture.captured.defaultNames.isEmpty())
        fixture.release()
        fixture.join(action)

        assertEquals(listOf(""), fixture.captured.defaultNames)
        assertEquals(1, fixture.captured.defaultReads.get())
        assertTrue(fixture.replacement.defaultNames.isEmpty())
        assertNull(fixture.container.connectedDefaultFloodScopeName.value)
        assertEquals(listOf(ChatRegionOwnerFixture.KEEP_A), fixture.container.connectedKnownRegions.get())
        assertBUnchanged(fixture)
        assertEquals(
            ChannelFloodScope.Inherit,
            fixture.container.database.channelDao().getById(data.CHANNEL_A)!!.floodScope,
        )
    }

    @Test
    fun aLateChannelFloodCommitCannotPushToAReplacementRadio() = channelFlood(sameRadio = false)

    @Test
    fun aLateChannelFloodCommitCannotPushToANewOwnerOnTheSameRadio() = channelFlood(sameRadio = true)

    @Test
    fun aMatchingChannelFloodCommitPushesOnlyItsCapturedSession() = withFixture { fixture ->
        val scope = ChannelFloodScope.Region(ChatRegionOwnerFixture.LOCAL_SCOPE)
        fixture.holdChannelCommit(scope)
        val controller = controller(fixture)
        val action = fixture.launchAction { controller.selectFloodScope(scope) }
        fixture.awaitCommitted()
        assertLocalScope(fixture, scope)
        assertTrue(fixture.captured.floodTexts.isEmpty())
        fixture.release()
        fixture.join(action)

        assertEquals(listOf(ChatRegionOwnerFixture.LOCAL_SCOPE), fixture.captured.floodTexts)
        assertTrue(fixture.replacement.floodTexts.isEmpty())
        assertTrue(fixture.captured.defaultNames.isEmpty())
        assertEquals(ChatRegionOwnerFixture.REGION_A, fixture.container.connectedDefaultFloodScopeName.value)
        assertBUnchanged(fixture)
    }

    private fun regionRemoval(sameRadio: Boolean) = withFixture { fixture ->
        fixture.holdChannelCommit(ChannelFloodScope.Inherit)
        val action = fixture.launchAction { fixture.container.removeKnownRegion(ChatRegionOwnerFixture.REGION_A) }
        fixture.awaitCommitted()
        assertRemovedLocally(fixture)
        fixture.replaceOwner(sameRadio)
        fixture.release()
        fixture.join(action)

        assertTrue(fixture.captured.defaultNames.isEmpty())
        assertTrue(fixture.replacement.defaultNames.isEmpty())
        assertEquals(0, fixture.captured.defaultReads.get())
        assertEquals(0, fixture.replacement.defaultReads.get())
        assertReplacementGlobals(fixture, sameRadio)
        assertRemovedLocally(fixture)
        assertBUnchanged(fixture)
    }

    private fun channelFlood(sameRadio: Boolean) = withFixture { fixture ->
        val scope = ChannelFloodScope.Region(ChatRegionOwnerFixture.LOCAL_SCOPE)
        fixture.holdChannelCommit(scope)
        val controller = controller(fixture)
        val action = fixture.launchAction { controller.selectFloodScope(scope) }
        fixture.awaitCommitted()
        assertLocalScope(fixture, scope)
        fixture.replaceOwner(sameRadio)
        fixture.release()
        fixture.join(action)

        assertTrue(fixture.captured.floodTexts.isEmpty())
        assertTrue(fixture.replacement.floodTexts.isEmpty())
        assertTrue(fixture.captured.defaultNames.isEmpty())
        assertTrue(fixture.replacement.defaultNames.isEmpty())
        assertReplacementGlobals(fixture, sameRadio)
        assertLocalScope(fixture, scope)
        assertBUnchanged(fixture)
    }

    private suspend fun controller(fixture: ChatRegionOwnerFixture): ChannelInfoController = fixture.onMain {
        val destination = ChatRadioNotificationData.destination(ChatRadioNotificationData.RADIO_A)
        ChannelInfoController(
            channel = destination.channel!!,
            dependencies = fixture.container.buildChannelInfoDependencies(destination)!!,
        )
    }

    private suspend fun assertRemovedLocally(fixture: ChatRegionOwnerFixture) {
        val data = ChatRadioNotificationData
        val device = fixture.container.database.deviceDao().getById(data.DEVICE_A)!!
        assertEquals(listOf(ChatRegionOwnerFixture.KEEP_A), device.knownRegions)
        assertNull(device.defaultFloodScopeName)
        assertEquals(
            ChannelFloodScope.Inherit,
            fixture.container.database.channelDao().getById(data.CHANNEL_A)!!.floodScope,
        )
    }

    private suspend fun assertLocalScope(fixture: ChatRegionOwnerFixture, scope: ChannelFloodScope) {
        val channel = fixture.container.database.channelDao().getById(ChatRadioNotificationData.CHANNEL_A)!!
        assertEquals(scope, channel.floodScope)
        val device = fixture.container.database.deviceDao().getById(ChatRadioNotificationData.DEVICE_A)!!
        assertEquals(listOf(ChatRegionOwnerFixture.REGION_A, ChatRegionOwnerFixture.KEEP_A), device.knownRegions)
        assertEquals(ChatRegionOwnerFixture.REGION_A, device.defaultFloodScopeName)
    }

    private suspend fun assertBUnchanged(fixture: ChatRegionOwnerFixture) {
        val data = ChatRadioNotificationData
        val device = fixture.container.database.deviceDao().getById(data.DEVICE_B)!!
        assertEquals(listOf(ChatRegionOwnerFixture.REGION_B), device.knownRegions)
        assertEquals(ChatRegionOwnerFixture.REGION_B, device.defaultFloodScopeName)
        assertEquals(
            ChannelFloodScope.Region(ChatRegionOwnerFixture.REGION_B),
            fixture.container.database.channelDao().getById(data.CHANNEL_B)!!.floodScope,
        )
    }

    private fun assertReplacementGlobals(fixture: ChatRegionOwnerFixture, sameRadio: Boolean) {
        val region = if (sameRadio) ChatRegionOwnerFixture.REPLACEMENT_A else ChatRegionOwnerFixture.REGION_B
        assertEquals(region, fixture.container.connectedDefaultFloodScopeName.value)
        assertEquals(listOf(region), fixture.container.connectedKnownRegions.get())
    }

    private fun withFixture(block: suspend (ChatRegionOwnerFixture) -> Unit) = runBlocking {
        val fixture = ChatRegionOwnerFixture()
        try {
            fixture.open()
            block(fixture)
            fixture.assertNoPhysicalWork()
        } finally {
            fixture.close()
        }
    }
}
