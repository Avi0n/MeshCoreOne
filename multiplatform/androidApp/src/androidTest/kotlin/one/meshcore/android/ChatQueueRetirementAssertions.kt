package one.meshcore.android

import kotlinx.coroutines.withTimeout
import one.meshcore.shared.chat.ChannelMessageEnvelope
import one.meshcore.shared.chat.ChatSendQueueService
import one.meshcore.shared.chat.ChatSendQueueServiceException
import one.meshcore.shared.chat.enqueueChannel
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.persistence.floodScope
import one.meshcore.shared.persistence.status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

internal suspend fun ChatQueueRetirementFixture.assertRetiredWhileReady() {
    assertSame(graph, base.container.connectionManager.services)
    assertEquals(
        ConnectionState.Ready(ChatRadioNotificationData.DEVICE_B.value),
        base.container.connectionManager.state.value,
    )
    base.ui.assertThread(
        ChatRadioNotificationData.HISTORY_B,
        ChatRadioNotificationData.DRAFT_B,
        sendLabel,
        enabled = false,
    )
    onMain {
        assertNotNull(environment.store())
        assertNotNull(environment.registry())
        assertEquals(ChatRadioNotificationData.RADIO_B, environment.radioId())
        assertNull(environment.sendQueue())
        assertNull(environment.messageService())
        assertNull(environment.blockedChannelSenderService())
        assertNull(environment.reactionService())
        assertNull(environment.reactionStore())
        assertNull(environment.reactionSender())
        assertNull(environment.floodScopeSession())
        assertNull(environment.localNodeName())
        assertNull(environment.deviceDefaultFloodScopeName())
        assertFalse(environment.supportsUnscopedFloodSend())
        val info = checkNotNull(environment.channelInfoDependencies())
        assertFalse(info.isRadioReady())
        assertNull(info.channelService())
        assertNull(info.deviceDefaultFloodScopeName())
        assertTrue(info.knownRegions().isEmpty())
    }
    stage("same-ready-owner-retired-providers-and-composer")
}

internal suspend fun ChatQueueRetirementFixture.assertFreshServices() = onMain {
    assertSame(graph.sendQueue, environment.sendQueue())
    assertSame(graph.messageService, environment.messageService())
    assertSame(graph.session, environment.floodScopeSession())
    assertTrue(checkNotNull(environment.channelInfoDependencies()).isRadioReady())
    stage("fresh-same-radio-live-providers-restored")
}

internal suspend fun ChatQueueRetirementFixture.assertPrewriteRejection(
    queue: ChatSendQueueService,
    expectedBatteryReads: Int,
    expectedFloodCalls: Int,
) {
    val failure = onMain {
        runCatching {
            withTimeout(ChatQueueRetirementFixture.TIMEOUT_MS) {
                queue.enqueueChannel(
                    ChannelMessageEnvelope(
                        messageID = ChatRadioNotificationData.MESSAGE_B,
                        channelIndex = ChatRadioNotificationData.INDEX,
                    ),
                )
            }
        }.exceptionOrNull()
    }
    assertTrue(
        "Retired queue must reject before creating a durable row",
        failure is ChatSendQueueServiceException.NotConnected,
    )
    assertRoomAndSessionUnchanged(expectedBatteryReads, expectedFloodCalls)
    stage("retired-queue-typed-prewrite-rejection")
}

internal suspend fun ChatQueueRetirementFixture.assertRoomAndSessionUnchanged(
    expectedBatteryReads: Int,
    expectedFloodCalls: Int,
) {
    val data = ChatRadioNotificationData
    val messages = base.container.database.messageDao().listAll().associateBy { it.id }
    assertEquals(setOf(data.MESSAGE_A, data.MESSAGE_B), messages.keys)
    for (radio in listOf(data.RADIO_A, data.RADIO_B)) {
        val expected = data.message(radio)
        val actual = checkNotNull(messages[expected.id])
        assertEquals(expected.radioID, actual.radioID)
        assertEquals(expected.text, actual.text)
        assertEquals(MessageStatus.SENT, actual.status)
        assertTrue(base.container.database.pendingSendDao().listByRadio(radio).isEmpty())
        val draft = if (radio == data.RADIO_A) data.DRAFT_A else data.DRAFT_B
        assertEquals(draft, base.container.draftStore.draft(data.destination(radio).conversationID))
    }
    assertTrue(base.container.database.pendingSendDao().listOrphans().isEmpty())
    assertEquals(
        data.channel(data.RADIO_A).floodScope,
        base.container.database.channelDao().getById(data.CHANNEL_A)?.floodScope,
    )
    assertEquals(
        if (inheritsFloodScope) ChannelFloodScope.Inherit else data.channel(data.RADIO_B).floodScope,
        base.container.database.channelDao().getById(data.CHANNEL_B)?.floodScope,
    )
    assertEquals(data.B_DEFAULT_SCOPE, base.container.connectedDefaultFloodScopeName.value)
    assertEquals(
        data.B_DEFAULT_SCOPE,
        base.container.database.deviceDao().getById(data.DEVICE_B)?.defaultFloodScopeName,
    )
    if (gatedSession.freshFloodCalls.isNotEmpty()) assertFreshFloodCommands()
    assertEquals(expectedFloodCalls, base.recording.floodCalls.get())
    assertEquals(0, base.recording.otherCalls.get())
    assertEquals(emptyList<String>(), base.recording.otherMethods.toList())
    assertEquals(expectedBatteryReads, base.recording.batteryReads.get())
    stage("room-history-drafts-and-outbound-unchanged")
}

internal fun ChatQueueRetirementFixture.assertFreshFloodCommands() {
    assertEquals(
        listOf(ChatQueueRetirementGatedSession.FloodCall("region", expectedFreshFloodText, 0)),
        gatedSession.freshFloodCalls.toList(),
    )
}
