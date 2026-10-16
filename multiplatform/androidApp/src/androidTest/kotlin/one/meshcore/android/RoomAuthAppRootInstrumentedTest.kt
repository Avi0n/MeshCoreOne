package one.meshcore.android

import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_thread_back_accessibility
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_join_room
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_remember_password
import one.meshcore.shared.resources.whatsnew_whats_new_continue_button
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAuthAppRootInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: RoomAuthAppRootFixture
    private lateinit var back: String
    private lateinit var joinLabel: String
    private lateinit var remember: String
    private lateinit var continueLabel: String
    private val inDialog get() = hasAnyAncestor(isDialog())
    private val password get() = hasSetTextAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Password) and !inDialog

    @Test
    fun actualAppRootLoadsScopedSavedPasswordsAndDiscardsMissingKey() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Requires the dedicated disposable AppRoot driver",
            args.containsKey(RoomAuthAppRootFixture.RUN_ARGUMENT),
        )
        assumeTrue("Requires API 34 window capture", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val nonce = checkNotNull(args.getString(RoomAuthAppRootFixture.RUN_ARGUMENT))
        fixture = RoomAuthAppRootFixture(nonce)
        try {
            labels()
            fixture.open()
            awaitChats()
            openAuth(FIRST, RoomAuthAppRootFixture.PASSWORDS[FIRST])
            fixture.screenshot("alpha-saved")
            safe("edit synthetic field") {
                compose.onNode(password).performTextReplacement(RoomAuthAppRootFixture.UNSAVED)
            }
            awaitPassword(RoomAuthAppRootFixture.UNSAVED)
            click(hasText(remember) and !inDialog, "uncheck Remember")
            assertRemember(ToggleableState.Off)
            dismissAuth()
            runBlocking { withTimeout(TIMEOUT_MS) { fixture.assertCredentialsRetained() } }
            openAuth(FIRST, RoomAuthAppRootFixture.PASSWORDS[FIRST])
            dismissAuth()
            openAuth(SECOND, RoomAuthAppRootFixture.PASSWORDS[SECOND])
            fixture.screenshot("bravo-saved")
            dismissAuth()
            openAuth(FIRST, RoomAuthAppRootFixture.PASSWORDS[FIRST])
            dismissAuth()
            fixture.removeKey()
            openAuthAfterKeyLoss(FIRST)
            fixture.screenshot("alpha-key-missing")
            dismissAuth()
            assertFalse(rawAbsent(SECOND))
            openAuthAfterKeyLoss(SECOND)
            fixture.screenshot("bravo-key-missing")
            dismissAuth()
            fixture.assertUnchangedRows()
            fixture.record()
        } finally {
            fixture.close()
        }
    }

    private fun labels() = runBlocking {
        back = getString(Res.string.chats_thread_back_accessibility)
        joinLabel = getString(Res.string.remotenodes_remote_nodes_auth_join_room)
        remember = getString(Res.string.remotenodes_remote_nodes_auth_remember_password)
        continueLabel = getString(Res.string.whatsnew_whats_new_continue_button)
    }

    private fun awaitChats() = safe("actual Chats root") {
        compose.waitUntil(TIMEOUT_MS) {
            if (nodes(hasText(continueLabel) and inDialog).fetchSemanticsNodes(atLeastOneRootRequired = false).size ==
                1
            ) {
                compose.onNode(hasText(continueLabel) and inDialog).performClick()
            }
            nodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                nodes(password).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                nodes(hasText(joinLabel) and !inDialog).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                RoomAuthAppRootFixture.NAMES.all {
                    nodes(hasText(it)).fetchSemanticsNodes(atLeastOneRootRequired = false).size ==
                        1
                }
        }
    }

    private fun openAuth(index: Int, expected: String) {
        click(hasText(RoomAuthAppRootFixture.NAMES[index]), "open actual room authentication")
        awaitPassword(expected)
        assertRemember(ToggleableState.On)
    }

    private fun openAuthAfterKeyLoss(index: Int) {
        assertFalse(rawAbsent(index))
        click(hasText(RoomAuthAppRootFixture.NAMES[index]), "open actual room authentication without key")
        safe("UI load removed its own undecryptable record") {
            compose.waitUntil(TIMEOUT_MS) { rawAbsent(index) }
        }
        awaitPassword("")
    }

    private fun awaitPassword(expected: String) = safe("exact scoped input and visual masking") {
        compose.waitUntil(TIMEOUT_MS) {
            if (nodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() ||
                nodes(hasText(joinLabel) and !inDialog).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
            ) {
                return@waitUntil false
            }
            val field =
                nodes(password).fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
                    ?: return@waitUntil false
            val config = field.config
            field.boundsInRoot.width > 0 &&
                field.boundsInRoot.height > 0 &&
                config.contains(SemanticsProperties.Password) &&
                config[SemanticsProperties.InputText].text == expected &&
                config[SemanticsProperties.EditableText].text == MASK.toString().repeat(expected.length)
        }
    }

    private fun assertRemember(expected: ToggleableState) = safe("Remember checkbox state") {
        val node = nodes(hasText(remember) and !inDialog).fetchSemanticsNodes().singleOrNull()
        assertTrue(node?.config?.get(SemanticsProperties.ToggleableState) == expected)
    }

    private fun dismissAuth() {
        click(hasContentDescription(back) and !inDialog, "dismiss authentication by Up")
        safe("authentication dismissed") {
            compose.waitUntil(TIMEOUT_MS) {
                nodes(password).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                    nodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
            }
        }
        awaitChats()
    }

    private fun rawAbsent(index: Int): Boolean = runBlocking { withTimeout(TIMEOUT_MS) { fixture.entryAbsent(index) } }

    private fun nodes(matcher: SemanticsMatcher): SemanticsNodeInteractionCollection = compose.onAllNodes(matcher)

    private fun click(matcher: SemanticsMatcher, description: String) = safe(description) {
        compose.waitUntil(TIMEOUT_MS) {
            val node = nodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
            node != null && node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0
        }
        compose.onNode(matcher).performClick()
    }

    private fun <T> safe(description: String, action: () -> T): T = try {
        action()
    } catch (error: AssertionError) {
        throw AssertionError("Owned auth UI $description failed (${error.javaClass.simpleName})")
    } catch (error: ComposeTimeoutException) {
        throw AssertionError("Owned auth UI $description timed out")
    } catch (error: RuntimeException) {
        throw AssertionError("Owned auth UI $description unavailable (${error.javaClass.simpleName})")
    }

    private companion object {
        const val FIRST = 0
        const val SECOND = 1
        const val TIMEOUT_MS = RoomAuthAppRootFixture.TIMEOUT_MS
        const val MASK = '\u2022'
    }
}
