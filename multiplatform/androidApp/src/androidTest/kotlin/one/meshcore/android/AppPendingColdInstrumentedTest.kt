package one.meshcore.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_input_accessibility_hint
import one.meshcore.shared.resources.chats_input_send_message
import one.meshcore.shared.resources.chats_message_status_sending
import one.meshcore.shared.resources.whatsnew_whats_new_continue_button
import org.jetbrains.compose.resources.getString
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPendingColdInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun seedDefaultAppPendingAndHoldOpenRoom() = runBlocking {
        val fixture = fixture("seed")
        try {
            withTimeout(AppPendingColdFixture.TIMEOUT_MS) { fixture.seed() }
            fixture.launch()
            openPending()
            compose.onNode(composer).performTextReplacement(AppPendingColdRows.DRAFT)
            assertPendingUi()
            fixture.persistBackgroundDraft()
            fixture.holdSeed()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun freshDefaultAppRestoresPendingBeforeOpeningActivity() = runBlocking {
        val fixture = fixture("verify")
        try {
            withTimeout(AppPendingColdFixture.TIMEOUT_MS) { fixture.verifyCold() }
            fixture.launch()
            openPending()
            assertPendingUi()
            withTimeout(AppPendingColdFixture.TIMEOUT_MS) { fixture.complete() }
        } finally {
            fixture.close()
        }
    }

    private fun fixture(phase: String): AppPendingColdFixture {
        val nonce = InstrumentationRegistry.getArguments().getString(AppPendingColdFixture.ARGUMENT)
        assumeTrue("Requires the isolated default-app process driver", nonce != null)
        return AppPendingColdFixture(checkNotNull(nonce), phase)
    }

    private val composer get() = hasSetTextAction() and !hasAnyAncestor(isDialog()) and
        SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            runBlocking { getString(Res.string.chats_input_accessibility_hint) },
        )

    private fun openPending() {
        val continueLabel = runBlocking { getString(Res.string.whatsnew_whats_new_continue_button) }
        val continueInDialog = hasText(continueLabel) and hasAnyAncestor(isDialog())
        compose.waitUntil(AppPendingColdFixture.TIMEOUT_MS) {
            val dialog = compose.onAllNodes(continueInDialog).fetchSemanticsNodes(atLeastOneRootRequired = false)
            if (dialog.size == 1) compose.onNode(continueInDialog).performClick()
            compose.onAllNodes(hasText(AppPendingColdRows.CHANNEL)).fetchSemanticsNodes(atLeastOneRootRequired = false)
                .size == 1 &&
                dialog.isEmpty() &&
                compose.onAllNodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        check(compose.onAllNodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty())
        compose.onNode(hasText(AppPendingColdRows.CHANNEL)).performClick()
        compose.waitUntil(AppPendingColdFixture.TIMEOUT_MS) {
            compose.onAllNodes(hasText(AppPendingColdRows.TEXT)).fetchSemanticsNodes(atLeastOneRootRequired = false)
                .size == 1 &&
                compose.onAllNodes(composer).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
    }

    private fun assertPendingUi() {
        val sendLabel = runBlocking { getString(Res.string.chats_input_send_message) }
        compose.waitUntil(AppPendingColdFixture.TIMEOUT_MS) {
            compose.onAllNodes(composer and hasText(AppPendingColdRows.DRAFT))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        compose.onNode(composer).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(AppPendingColdRows.DRAFT)),
        )
        compose.onNode(hasText(AppPendingColdRows.TEXT)).assertExists()
        val sending = runBlocking { getString(Res.string.chats_message_status_sending) }
        compose.onNode(hasContentDescription(sending), useUnmergedTree = true).assertExists()
        compose.onNode(hasContentDescription(sendLabel)).assertIsNotEnabled()
    }
}
