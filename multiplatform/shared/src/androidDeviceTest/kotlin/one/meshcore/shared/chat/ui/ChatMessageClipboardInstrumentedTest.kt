package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.ChatRenderState
import one.meshcore.shared.chat.ChatThreadRenderState
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.chat.EnvInputs
import one.meshcore.shared.chat.MessageFragmentBuilder
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.chat.SenderNameResolver
import one.meshcore.shared.chat.buildThreadInputs
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_copy
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMessageClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val clipboard = HoldingClipboard()
    private lateinit var copyLabel: String

    @After
    fun releaseWrite() {
        clipboard.release.complete(Unit)
    }

    @Test
    fun copyWritesTheOriginalAndSurvivesClosingTheSheet() {
        show()
        openAndCopy()
        compose.runOnIdle {
            assertEquals(MESSAGE_TEXT, copiedText())
            assertEquals(0, clipboard.completed)
        }
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        release()
        compose.runOnIdle {
            assertEquals(1, clipboard.completed)
            assertEquals(0, clipboard.cancelled)
            assertEquals(MESSAGE_TEXT, copiedText())
        }
    }

    @Test
    fun copyWritesTheTranslationWhenItIsShowing() {
        show(phase = MessageTranslationPhase.Showing(TRANSLATED_TEXT, LANGUAGE_CODE))
        openAndCopy()
        compose.runOnIdle {
            assertEquals(TRANSLATED_TEXT, copiedText())
            assertEquals(0, clipboard.completed)
        }
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        release()
        compose.runOnIdle { assertEquals(1, clipboard.completed) }
    }

    @Test
    fun leavingTheThreadCancelsAPendingCopy() {
        show()
        openAndCopy()
        compose.runOnIdle { assertEquals(1, clipboard.entries.size) }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, clipboard.completed) }
    }

    private fun show(phase: MessageTranslationPhase? = null) {
        val state = threadState()
        compose.setContent {
            TimestampFormatting {
                copyLabel = stringResource(Res.string.chats_message_action_copy)
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    MaterialTheme {
                        if (visible.value) {
                            ChatThreadScreen(
                                screenState = ChatThreadScreenState(
                                    thread = state,
                                    composer = ComposerState(maxBytes = BYTE_BUDGET),
                                    title = SCREEN_TITLE,
                                    isReady = true,
                                    isEncrypted = true,
                                ),
                                actions = ChatThreadActions(
                                    onTextChange = {},
                                    onSend = {},
                                    onNearTop = {},
                                    onNearBottomChanged = {},
                                    onScrollToBottom = {},
                                    onRetry = {},
                                    onDelete = {},
                                    onClearError = {},
                                    onClearSendError = {},
                                    onBack = {},
                                ),
                                dependencies = ChatThreadScreenDependencies(translationPhase = { phase }),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun threadState(): ChatThreadRenderState {
        val message = MessageDto(
            id = MESSAGE_ID,
            radioID = RADIO_ID,
            text = MESSAGE_TEXT,
            timestamp = TIMESTAMP,
            createdAt = TIMESTAMP.toDouble(),
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENT,
            textType = TextType.PLAIN,
            pathLength = 0,
            isRead = true,
            heardRepeats = 0,
            sendCount = 1,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            linkPreviewFetched = false,
            containsSelfMention = false,
            mentionSeen = false,
            timestampCorrected = false,
        )
        val resolver = SenderNameResolver {
            NodeNameResolution(displayName = SENDER_NAME, matchKind = NodeNameMatchKind.EXACT)
        }
        val items = buildThreadInputs(listOf(message), dividerMessageID = null, resolveSender = resolver)
            .map { (dto, inputs) ->
                MessageFragmentBuilder.makeItem(
                    dto,
                    inputs,
                    EnvInputs(currentUserName = SENDER_NAME, showIncomingSendTime = false),
                )
            }
        return ChatThreadRenderState.EMPTY.copy(
            items = items,
            phase = ChatRenderState.LoadPhase.LOADED,
            hasMoreMessages = false,
        )
    }

    private fun openAndCopy() {
        compose.onNodeWithText(MESSAGE_TEXT).performTouchInput { longClick() }
        compose.onNodeWithText(copyLabel).performScrollTo().performClick()
    }

    private fun release() {
        compose.runOnIdle { clipboard.release.complete(Unit) }
        compose.waitForIdle()
    }

    private fun copiedText(): String = requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString()

    private companion object {
        const val SCREEN_TITLE = "Thread"
        const val SENDER_NAME = "Me"
        const val MESSAGE_TEXT = "Hello mesh"
        const val TRANSLATED_TEXT = "Hola malla"
        const val LANGUAGE_CODE = "es"
        const val BYTE_BUDGET = 150
        const val TIMESTAMP = 1_700_000_000L
        val MESSAGE_ID: Uuid = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
        val RADIO_ID: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    }
}
