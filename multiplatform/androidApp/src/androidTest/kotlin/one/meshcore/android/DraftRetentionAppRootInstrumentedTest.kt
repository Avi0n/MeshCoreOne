package one.meshcore.android

import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_input_accessibility_hint
import one.meshcore.shared.resources.chats_input_send_message
import one.meshcore.shared.resources.chats_thread_back_accessibility
import one.meshcore.shared.resources.whatsnew_whats_new_continue_button
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftRetentionAppRootInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: DraftRetentionAppRootFixture
    private lateinit var back: String
    private lateinit var send: String
    private lateinit var inputHint: String
    private lateinit var continueLabel: String
    private val inDialog get() = hasAnyAncestor(isDialog())
    private val input get() = hasSetTextAction() and !inDialog and
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, inputHint)

    @Test
    fun actualAppRetainsEditorsAcrossNavigationRecreationAndBackground() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires isolated AppRoot driver", args.containsKey(DraftRetentionAppRootFixture.RUN_ARGUMENT))
        assumeTrue("Requires owned window capture", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fixture = DraftRetentionAppRootFixture(checkNotNull(args.getString(DraftRetentionAppRootFixture.RUN_ARGUMENT)))
        try {
            labels()
            fixture.open()
            awaitChats()
            navigationCycle(DM_NAME, DM_ROUTE, DraftRetentionAppRootFixture.DM, DM_TEXT, "dm-navigation")
            up()
            awaitChats()
            navigationCycle(
                CHANNEL_NAME,
                fixture.channelRoute,
                DraftRetentionAppRootFixture.CHANNEL,
                CHANNEL_TEXT,
                "channel-navigation",
            )
            fixture.screenshot("channel-retained")
            fixture.recreate()
            awaitInput(CHANNEL_TEXT)
            fixture.assertActualGraph(fixture.channelRoute, DraftRetentionAppRootFixture.CHANNEL)
            up()
            awaitChats()
            click(hasText(DM_NAME))
            awaitInput(DM_TEXT)
            fixture.assertActualGraph(DM_ROUTE, DraftRetentionAppRootFixture.DM)
            fixture.awaitSaved(DraftRetentionAppRootFixture.DM, DM_TEXT)
            assertEquals(DM_TEXT, runBlocking { fixture.saved(DraftRetentionAppRootFixture.DM) })
            val setText =
                checkNotNull(compose.onNode(input).fetchSemanticsNode().config[SemanticsActions.SetText].action)
            val editor = compose.runOnIdle { fixture.editor(DraftRetentionAppRootFixture.DM) }
            val requestBackground = fixture.armBackground()
            val timing = compose.runOnIdle {
                val time = SystemClock.elapsedRealtimeNanos()
                assertTrue(setText(AnnotatedString(BACKGROUND_TEXT)))
                assertEquals(BACKGROUND_TEXT, editor.text.value)
                val accepted = requestBackground()
                Triple(time, SystemClock.elapsedRealtimeNanos(), accepted)
            }
            fixture.background(timing.first, timing.second, timing.third)
            fixture.awaitSaved(DraftRetentionAppRootFixture.DM, BACKGROUND_TEXT)
            fixture.resume()
            awaitInput(BACKGROUND_TEXT)
            fixture.assertActualGraph(DM_ROUTE, DraftRetentionAppRootFixture.DM)
            compose.runOnIdle { assertSame(editor, fixture.editor(DraftRetentionAppRootFixture.DM)) }
            assertEquals(CHANNEL_TEXT, runBlocking { fixture.saved(DraftRetentionAppRootFixture.CHANNEL) })
            fixture.screenshot("background-retained")
            fixture.assertNoOutbound()
            fixture.record()
        } finally {
            fixture.close()
        }
    }

    private fun navigationCycle(name: String, route: ChatRoute, id: ChatConversationID, text: String, phase: String) {
        click(hasText(name))
        awaitInput("")
        fixture.assertActualGraph(route, id)
        assertNull(runBlocking { fixture.saved(id) })
        val editor = compose.runOnIdle { fixture.editor(id) }
        val setText = checkNotNull(compose.onNode(input).fetchSemanticsNode().config[SemanticsActions.SetText].action)
        val navigateUp = checkNotNull(
            compose.onNode(hasContentDescription(back)).fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )
        val span = compose.runOnIdle {
            val start = SystemClock.elapsedRealtimeNanos()
            assertTrue(setText(AnnotatedString(text)))
            assertEquals(text, editor.text.value)
            assertTrue(navigateUp())
            assertNull(fixture.container.coordinator.chatsSelectedRoute.value)
            SystemClock.elapsedRealtimeNanos() - start
        }
        fixture.rapidNavigation(phase, span)
        awaitChats()
        click(hasText(name))
        awaitInput(text)
        fixture.assertActualGraph(route, id)
        compose.runOnIdle { assertSame(editor, fixture.editor(id)) }
        fixture.assertNoOutbound()
    }

    private fun labels() = runBlocking {
        back = getString(Res.string.chats_thread_back_accessibility)
        send = getString(Res.string.chats_input_send_message)
        inputHint = getString(Res.string.chats_input_accessibility_hint)
        continueLabel = getString(Res.string.whatsnew_whats_new_continue_button)
    }

    private fun awaitChats() {
        compose.waitUntil(TIMEOUT_MS) {
            val dialogs = compose.onAllNodes(hasText(continueLabel) and inDialog)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
            if (dialogs.size == 1) compose.onNode(hasText(continueLabel) and inDialog).performClick()
            compose.onAllNodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                compose.onAllNodes(input).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                listOf(DM_NAME, CHANNEL_NAME).all {
                    compose.onAllNodes(hasText(it)).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
                }
        }
    }

    private fun awaitInput(expected: String) {
        compose.waitUntil(TIMEOUT_MS) {
            val field = compose.onAllNodes(input).fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
            field != null &&
                field.boundsInRoot.width > 0 &&
                field.boundsInRoot.height > 0 &&
                field.config[SemanticsProperties.EditableText].text == expected
        }
        val sendNode = compose.onNode(hasContentDescription(send)).fetchSemanticsNode()
        assertTrue(sendNode.config.contains(SemanticsProperties.Disabled))
    }

    private fun up() = click(hasContentDescription(back))

    private fun click(matcher: SemanticsMatcher) {
        compose.waitUntil(TIMEOUT_MS) {
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
            node != null && node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0
        }
        compose.onNode(matcher).performClick()
    }

    private companion object {
        const val TIMEOUT_MS = DraftRetentionAppRootFixture.TIMEOUT_MS
        const val DM_NAME = DraftRetentionAppRootFixture.DM_NAME
        const val CHANNEL_NAME = DraftRetentionAppRootFixture.CHANNEL_NAME
        const val DM_TEXT = DraftRetentionAppRootFixture.DM_TEXT
        const val CHANNEL_TEXT = DraftRetentionAppRootFixture.CHANNEL_TEXT
        const val BACKGROUND_TEXT = DraftRetentionAppRootFixture.BACKGROUND_TEXT
        val DM_ROUTE = ChatRoute.Direct(DraftRetentionAppRootFixture.CONTACT.value)
    }
}
