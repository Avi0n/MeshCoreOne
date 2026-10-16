package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.NotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelInfoControllerTest {
    private val channel: ChannelDto = channelDto(
        id = ChannelTestIds.fresh(),
        radioID = ChannelTestIds.RADIO,
        index = 2,
        name = "Ops",
        notificationLevel = NotificationLevel.ALL,
        isPinned = false,
        floodScope = ChannelFloodScope.Inherit,
    )

    private class Recorder {
        val notificationLevels = mutableListOf<NotificationLevel>()
        val pins = mutableListOf<Boolean>()
        val floodScopes = mutableListOf<ChannelFloodScope>()
        val clearedNotifications = mutableListOf<Int>()
        var failFloodScope = false
    }

    private fun controller(fixture: ChannelFlowFixture, recorder: Recorder, isRadioReady: () -> Boolean = { true }) =
        ChannelInfoController(
            channel,
            ChannelInfoController.Dependencies(
                channelService = fixture.serviceProvider,
                radioId = fixture.radioProvider,
                persistNotificationLevel = { _, level -> recorder.notificationLevels += level },
                persistPinned = { _, fav -> recorder.pins += fav },
                persistFloodScope = { _, scope ->
                    if (recorder.failFloodScope) error("persist failed")
                    recorder.floodScopes += scope
                },
                clearChannelNotifications = { channelIndex, _ -> recorder.clearedNotifications += channelIndex },
                isRadioReady = isRadioReady,
            ),
        )

    @Test
    fun delete_clears_the_slot_on_the_radio_and_reports_completion() = runTest {
        val fixture = ChannelFlowFixture()
        fixture.seedChannel(index = 2, name = "Ops")
        val recorder = Recorder()
        val controller = controller(fixture, recorder)

        var deleted = false
        controller.delete { deleted = true }

        assertTrue(deleted)
        val call = fixture.session.setChannelCalls.single()
        assertEquals(2, call.index)
        assertEquals("", call.name)
        assertTrue(call.secret.all { it.toInt() == 0 })
        // Delete clears the channel's delivered tray notifications (iOS clearNotificationsForChannel).
        assertEquals(listOf(2), recorder.clearedNotifications)
    }

    @Test
    fun clear_messages_deletes_the_slots_messages_and_reports_completion() = runTest {
        val fixture = ChannelFlowFixture()
        fixture.store.seedMessages(channelIndex = 2, count = 5)
        val recorder = Recorder()
        val controller = controller(fixture, recorder)

        var cleared = false
        controller.clearMessages { cleared = true }

        assertTrue(cleared)
        assertEquals(0, fixture.store.remainingMessageCount(2))
        assertTrue(2 in fixture.store.deletedMessagesForSlots())
        // Clear-messages also clears the channel's delivered tray notifications (iOS parity).
        assertEquals(listOf(2), recorder.clearedNotifications)
    }

    @Test
    fun clearingChannelMessagesWhileDisconnectedDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        var readyReads = 0
        var notificationClears = 0
        val controller = ChannelInfoController(
            channel,
            ChannelInfoController.Dependencies(
                channelService = {
                    serviceReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
                persistNotificationLevel = { _, _ -> error("notification") },
                persistPinned = { _, _ -> error("pinned") },
                persistFloodScope = { _, _ -> error("flood") },
                clearChannelNotifications = { _, _ ->
                    notificationClears += 1
                    error("notifications")
                },
                isRadioReady = {
                    readyReads += 1
                    error("ready")
                },
            ),
        )

        var cleared = false
        controller.clearMessages { cleared = true }

        assertEquals(1, serviceReads)
        assertEquals(1, radioReads)
        assertEquals(0, readyReads)
        assertEquals(0, notificationClears)
        assertEquals(false, cleared)
        val state = controller.state.value
        assertEquals(ChannelActionError.NoDeviceConnected, state.error)
        assertEquals(false, state.isClearingMessages)
        assertEquals(false, state.isDeleting)
        assertEquals(NotificationLevel.ALL, state.notificationLevel)
        assertEquals(false, state.isPinned)
    }

    @Test
    fun deletingAChannelWhileDisconnectedDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        var readyReads = 0
        var notificationClears = 0
        val controller = ChannelInfoController(
            channel,
            ChannelInfoController.Dependencies(
                channelService = {
                    serviceReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
                persistNotificationLevel = { _, _ -> error("notification") },
                persistPinned = { _, _ -> error("pinned") },
                persistFloodScope = { _, _ -> error("flood") },
                clearChannelNotifications = { _, _ ->
                    notificationClears += 1
                    error("notifications")
                },
                isRadioReady = {
                    readyReads += 1
                    error("ready")
                },
            ),
        )

        var deleted = false
        controller.delete { deleted = true }

        assertEquals(1, serviceReads)
        assertEquals(1, radioReads)
        assertEquals(0, readyReads)
        assertEquals(0, notificationClears)
        assertEquals(false, deleted)
        val state = controller.state.value
        assertEquals(ChannelActionError.NoDeviceConnected, state.error)
        assertEquals(false, state.isDeleting)
        assertEquals(false, state.isClearingMessages)
        assertEquals(NotificationLevel.ALL, state.notificationLevel)
        assertEquals(false, state.isPinned)
    }

    @Test
    fun notification_level_and_pinned_write_through_optimistically() = runTest {
        val recorder = Recorder()
        val controller = controller(ChannelFlowFixture(), recorder)

        controller.setNotificationLevel(NotificationLevel.MUTED)
        assertEquals(NotificationLevel.MUTED, controller.state.value.notificationLevel)
        assertEquals(listOf(NotificationLevel.MUTED), recorder.notificationLevels)

        controller.setPinned(true)
        assertTrue(controller.state.value.isPinned)
        assertEquals(listOf(true), recorder.pins)
    }

    @Test
    fun pinWritesWhileDisconnectedAndNotificationDoesNot() = runTest {
        val recorder = Recorder()
        val controller = controller(ChannelFlowFixture(), recorder, isRadioReady = { false })

        controller.setNotificationLevel(NotificationLevel.MUTED)
        controller.setPinned(true)

        assertEquals(NotificationLevel.ALL, controller.state.value.notificationLevel)
        assertTrue(controller.state.value.isPinned)
        assertTrue(recorder.notificationLevels.isEmpty())
        assertEquals(listOf(true), recorder.pins)
    }

    @Test
    fun a_failed_flood_scope_write_rolls_the_selection_back() = runTest {
        val recorder = Recorder().apply { failFloodScope = true }
        val controller = controller(ChannelFlowFixture(), recorder)

        controller.selectFloodScope(ChannelFloodScope.AllRegions)

        assertEquals(ChannelFloodScope.Inherit, controller.state.value.floodScope)
        assertFalse(recorder.floodScopes.contains(ChannelFloodScope.AllRegions))
    }
}
