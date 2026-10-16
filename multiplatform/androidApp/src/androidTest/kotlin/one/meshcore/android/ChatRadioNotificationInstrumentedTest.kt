package one.meshcore.android

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.persistence.floodScope
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_input_send_message
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRadioNotificationInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private lateinit var fixture: ChatRadioNotificationFixture

    @Before
    fun setUp() = runBlocking {
        fixture = ChatRadioNotificationFixture()
        fixture.open()
    }

    @After
    fun tearDown() = runBlocking {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun aStaleChannelBodyTapKeepsCachedHistoryAndDraftWithoutUsingTheReadyRadio() = runBlocking {
        val data = ChatRadioNotificationData
        fixture.postAndTap(data.RADIO_A)
        fixture.ui.assertThread(
            data.HISTORY_A,
            data.DRAFT_A,
            getString(Res.string.chats_input_send_message),
            enabled = false,
        )
        val environment = withContext(Dispatchers.Main.immediate) {
            fixture.container.buildChatEnvironment(data.destination(data.RADIO_A))
        }
        assertNotNull(environment.store())
        assertNotNull(environment.registry())
        assertEquals(data.RADIO_A, environment.radioId())
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
        assertEquals(0, fixture.recording.floodCalls.get())
        val info = checkNotNull(environment.channelInfoDependencies())
        assertFalse(info.isRadioReady())
        assertNull(info.channelService())
        assertNull(info.deviceDefaultFloodScopeName())
        assertTrue(info.knownRegions().isEmpty())
        val beforeB = fixture.container.database.channelDao().getById(data.CHANNEL_B)
        withContext(Dispatchers.Main.immediate) {
            info.persistFloodScope(
                checkNotNull(data.destination(data.RADIO_A).channel),
                ChannelFloodScope.Region(data.LOCAL_SCOPE),
            )
        }
        assertEquals(
            ChannelFloodScope.Region(data.LOCAL_SCOPE),
            fixture.container.database.channelDao().getById(data.CHANNEL_A)?.floodScope,
        )
        val afterB = fixture.container.database.channelDao().getById(data.CHANNEL_B)
        assertEquals(beforeB?.radioID, afterB?.radioID)
        assertEquals(beforeB?.floodScope, afterB?.floodScope)
        assertEquals(0, fixture.recording.floodCalls.get())
        assertEquals(data.DRAFT_A, fixture.container.draftStore.draft(data.destination(data.RADIO_A).conversationID))
        fixture.assertNoOutbound()
        fixture.record("stale-a-systemui-body", sendEnabled = false)
    }

    @Test
    fun aMatchingChannelBodyTapUsesItsReadyServicesAndEnablesTheComposer() = runBlocking {
        val data = ChatRadioNotificationData
        fixture.postAndTap(data.RADIO_B)
        fixture.ui.assertThread(
            data.HISTORY_B,
            data.DRAFT_B,
            getString(Res.string.chats_input_send_message),
            enabled = true,
        )
        val environment = withContext(Dispatchers.Main.immediate) {
            fixture.container.buildChatEnvironment(data.destination(data.RADIO_B))
        }
        assertSame(fixture.graph.sendQueue, environment.sendQueue())
        assertSame(fixture.graph.messageService, environment.messageService())
        assertSame(fixture.graph.session, environment.floodScopeSession())
        assertEquals(data.RADIO_B, environment.radioId())
        assertEquals(data.B_NODE_NAME, environment.localNodeName())
        assertEquals(data.B_DEFAULT_SCOPE, environment.deviceDefaultFloodScopeName())
        assertTrue(environment.supportsUnscopedFloodSend())
        val info = checkNotNull(environment.channelInfoDependencies())
        assertEquals(data.B_DEFAULT_SCOPE, info.deviceDefaultFloodScopeName())
        assertEquals(listOf(data.B_KNOWN_REGION), info.knownRegions())
        assertEquals(data.DRAFT_B, fixture.container.draftStore.draft(data.destination(data.RADIO_B).conversationID))
        fixture.assertNoOutbound()
        fixture.record("matching-b-systemui-body", sendEnabled = true)
    }
}
