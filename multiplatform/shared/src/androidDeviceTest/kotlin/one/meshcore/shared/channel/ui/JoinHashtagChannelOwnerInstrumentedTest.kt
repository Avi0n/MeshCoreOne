package one.meshcore.shared.channel.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelBatchRead
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.channel.ChannelSession
import one.meshcore.shared.channel.ChannelStore
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JoinHashtagChannelOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementGraphClearsTheRetiredMatchWithoutDroppingTheName() {
        val firstService = service(channel(FIRST_RADIO, "#general"))
        val replacementService = service()
        val serviceOwner = mutableStateOf(firstService)
        val radioOwner = mutableStateOf(FIRST_RADIO)

        compose.setContent {
            val currentService = serviceOwner.value
            val currentRadio = radioOwner.value
            MaterialTheme {
                JoinHashtagChannelScreen(
                    providers = ChannelFlowProviders(
                        channelService = { currentService },
                        radioId = { currentRadio },
                        maxChannels = { MAX_CHANNELS },
                    ),
                    availableSlots = listOf(1),
                    onBack = {},
                    onJoined = {},
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("general")
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithText(ALREADY_JOINED).fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle {
            serviceOwner.value = replacementService
            radioOwner.value = REPLACEMENT_RADIO
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithText(ALREADY_JOINED).fetchSemanticsNodes().isEmpty()
        }

        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("general")),
        )
        assertTrue(compose.onAllNodesWithText(ALREADY_JOINED).fetchSemanticsNodes().isEmpty())
    }

    private fun service(vararg channels: ChannelDto): ChannelService = ChannelService(
        session = OwnerTestChannelSession,
        store = OwnerTestChannelStore(channels.toList()),
    )

    private fun channel(radioID: Uuid, name: String) = ChannelDto(
        id = Uuid.normalized("C0000000-0000-0000-0000-000000000001"),
        radioID = radioID,
        index = 1,
        name = name,
        secret = ByteArray(16) { 0x11 },
        isEnabled = true,
        unreadCount = 0,
        notificationLevel = NotificationLevel.ALL,
    )

    private companion object {
        const val ALREADY_JOINED = "Already joined"
        const val MAX_CHANNELS = 8
        const val LOAD_TIMEOUT_MS = 5_000L
        val FIRST_RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val REPLACEMENT_RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000002")
    }
}

private object OwnerTestChannelSession : ChannelSession {
    override suspend fun getChannel(index: Int): ChannelInfo = error("unused")

    override suspend fun getChannels(indices: List<Int>): ChannelBatchRead = error("unused")

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) = error("unused")
}

private class OwnerTestChannelStore(private val channels: List<ChannelDto>) : ChannelStore {
    override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> = channels.filter { it.radioID == radioID }

    override suspend fun fetchChannel(radioID: Uuid, index: Int): ChannelDto? =
        channels.firstOrNull { it.radioID == radioID && it.index == index }

    override suspend fun saveChannel(radioID: Uuid, info: ChannelInfo): Uuid = error("unused")

    override suspend fun batchSaveChannels(
        radioID: Uuid,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<Int>,
        pruneBeyond: Int?,
    ): List<ChannelDto> = error("unused")

    override suspend fun deleteChannel(id: Uuid) = error("unused")

    override suspend fun deleteMessagesForChannel(radioID: Uuid, channelIndex: Int) = error("unused")

    override suspend fun updateChannelLastMessage(channelID: Uuid, date: Double?) = error("unused")

    override suspend fun updateChannelFloodScope(channelID: Uuid, scope: ChannelFloodScope) = error("unused")

    override suspend fun clearChannelUnreadCount(channelID: Uuid) = error("unused")

    override suspend fun clearChannelUnreadMentionCount(channelID: Uuid) = error("unused")
}
