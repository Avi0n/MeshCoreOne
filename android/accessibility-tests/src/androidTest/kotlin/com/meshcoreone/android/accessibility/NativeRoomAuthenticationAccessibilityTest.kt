// AndroidOnly: WP-503 Audit actual room authentication sheet states and native IME reachability with deterministic UI ports.
package com.meshcoreone.android.accessibility

import android.view.WindowInsets
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot as isViewRoot
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.rooms.RoomAuthenticationSheet
import com.meshcoreone.android.feature.chats.rooms.RoomConversationState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRoomAuthenticationAccessibilityTest : DeviceAccessibilityHarness() {
    private val radio = RadioId(UUID(0, 503401))
    private val session = RemoteNodeSessionDTO(
        id = UUID(0, 503402), radioId = radio, publicKey = Bytes(ByteArray(32) { 0x51 }),
        name = "Room \u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd", role = RemoteNodeRole.ROOM_SERVER, isConnected = false,
    )
    private val contact = ContactDTO(
        id = UUID(0, 503403), radioId = radio, publicKey = session.publicKey,
        name = session.name, lastHeardTimestamp = null,
    )
    private val state = mutableStateOf(RoomConversationState(session))
    private val shown = mutableStateOf(true)
    private val submissions = mutableListOf<Pair<String, Boolean>>()
    private var cancellations = 0
    private lateinit var sheetHost: View
    private val passwordLabel get() = activity.getString(R.string.l10n_app_remotenodes_remotenodes_auth_password)
    private val rememberLabel get() = activity.getString(R.string.l10n_app_remotenodes_remotenodes_auth_rememberpassword)
    private val joinLabel get() = activity.getString(R.string.l10n_app_remotenodes_remotenodes_auth_joinroom)
    private val cancelLabel get() = activity.getString(R.string.l10n_app_chats_chats_common_cancel)

    @get:Rule(order = 1) val failureDiagnostics = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            println("WP503_ROOM_AUTH_FAILURE|case=${description.methodName}|failure=${failure.javaClass.name}")
            compose.runOnUiThread {
                val insets = requireNotNull(
                    (if (::sheetHost.isInitialized) sheetHost else activity.window.decorView).rootWindowInsets,
                )
                println("WP503_ROOM_AUTH_WINDOW|imeVisible=${insets.isVisible(WindowInsets.Type.ime())}" +
                    "|imeBottom=${insets.getInsets(WindowInsets.Type.ime()).bottom}")
            }
            println("WP503_ROOM_AUTH_FAILURE_TREE|" +
                compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE))
        }
    }

    @Test fun knownRoomPasswordRememberJoinAndCancelStayReachableWithNativeImeAtFont200() {
        mount(RoomConversationState(session, authenticationContact = contact, authenticationLookupComplete = true))
        title()
        val remember = rememberAction().assertIsOn()
        assertTarget(remember)
        assertLabel(rememberLabel)
        remember.performClick().assertIsOff()
        val password = compose.onNode(hasSetTextAction() and hasText(passwordLabel))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertTarget(password)
        assertLabel(passwordLabel)
        password.performClick().performTextReplacement("fixture")
        awaitActualIme()
        assertLabel(rememberLabel)
        assertTarget(remember)
        val join = action(joinLabel).assertIsEnabled()
        assertLabel(joinLabel)
        assertTarget(join)
        join.performClick()
        assertEquals(listOf("fixture" to false), submissions)
        val cancel = action(cancelLabel).assertIsEnabled()
        assertLabel(cancelLabel)
        assertTarget(cancel)
        cancel.performClick()
        assertDismissed()
    }

    @Test fun unresolvedRoomLookupBecomesReadableNotFoundAndCancelDismissesWithoutJoining() {
        mount(RoomConversationState(session))
        title()
        compose.onAllNodes(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )).assertCountEquals(1)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(authenticationLookupComplete = true) }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        assertLabel(activity.getString(R.string.l10n_app_chats_chats_roomauth_notfound_title))
        assertLabel(activity.getString(R.string.l10n_app_chats_chats_roomauth_notfound_description))
        val cancel = action(cancelLabel).assertIsEnabled()
        assertLabel(cancelLabel)
        assertTarget(cancel)
        cancel.performClick()
        assertDismissed()
        assertTrue(submissions.isEmpty())
    }

    @Test fun pendingRoomJoinStaysDisabledAndNamedCancelDismissesWithoutDuplicateSubmission() {
        mount(RoomConversationState(
            session, isAuthenticating = true, authenticationContact = contact, authenticationLookupComplete = true,
        ))
        title()
        val join = action(joinLabel).assertIsNotEnabled()
        assertLabel(joinLabel)
        assertTarget(join)
        join.performClick()
        assertTrue("Disabled native Join must not invoke the UI port", submissions.isEmpty())
        assertTarget(rememberAction().assertIsOn())
        assertLabel(rememberLabel)
        val cancel = action(cancelLabel).assertIsEnabled()
        assertLabel(cancelLabel)
        assertTarget(cancel)
        cancel.performClick()
        assertDismissed()
    }

    private fun mount(initial: RoomConversationState) {
        state.value = initial
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    Text("Room authentication host")
                    if (shown.value) RoomAuthenticationSheet(
                        state.value,
                        onDismiss = { cancellations++; shown.value = false },
                        onAuthenticate = { password, remember -> submissions += password to remember },
                    )
                }
            }
            compose.waitForIdle()
            onView(isViewRoot()).inRoot(isDialog()).check { view, missing ->
                if (missing != null) throw missing
                sheetHost = requireNotNull(view)
            }
        }
    }

    private fun title() {
        compose.onNodeWithText(session.name)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertLabel(session.name)
    }

    private fun action(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun rememberAction() = compose.onNode(SemanticsMatcher("Named native Remember switch") {
        it.config.contains(SemanticsProperties.ToggleableState) && narrative(it).contains(rememberLabel)
    }).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    private fun assertLabel(label: String) {
        val text = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true)
        text.assertIsDisplayed()
        assertFont200Unclipped(text)
        val node = text.fetchSemanticsNode()
        val viewport = generateSequence(node) { it.parent }.last().boundsInRoot
        val bounds = node.boundsInRoot
        println("WP503_ROOM_AUTH_TEXT|label=$label|bounds=$bounds|size=${node.size}|viewport=$viewport")
        assertTrue("The complete room authentication label must be visible in its actual sheet",
            bounds.left >= viewport.left && bounds.top >= viewport.top && bounds.right <= viewport.right &&
                bounds.bottom <= viewport.bottom && bounds.width == node.size.width.toFloat() &&
                bounds.height == node.size.height.toFloat())
    }

    private fun assertTarget(interaction: SemanticsNodeInteraction) {
        val node = interaction.assertIsDisplayed().fetchSemanticsNode()
        val viewport = generateSequence(node) { it.parent }.last().boundsInRoot
        val target = node.touchBoundsInRoot
        val density = activity.resources.displayMetrics.density
        println("WP503_ROOM_AUTH_TARGET|bounds=$target|viewport=$viewport|size=${node.size}")
        assertTrue("Room authentication actions must be fully contained and at least48dp",
            target.left >= viewport.left && target.top >= viewport.top && target.right <= viewport.right &&
                target.bottom <= viewport.bottom && target.width / density >= 48f && target.height / density >= 48f)
    }

    private fun awaitActualIme() {
        val input = requireNotNull(activity.getSystemService(InputMethodManager::class.java))
        compose.waitUntil {
            var ready = false
            compose.runOnUiThread {
                ready = input.isAcceptingText && requireNotNull(sheetHost.rootWindowInsets)
                    .isVisible(WindowInsets.Type.ime())
            }
            ready
        }
        println("WP503_ROOM_AUTH_IME|acceptingText=true|imeVisible=true|physicalTalkBack=false")
    }

    private fun assertDismissed() {
        compose.onNodeWithText(session.name).assertDoesNotExist()
        compose.onNodeWithText("Room authentication host").assertIsDisplayed()
        assertEquals(1, cancellations)
        println("WP503_ROOM_AUTH_DISMISSED|cancellations=1|submissions=${submissions.size}|realRadio=false")
    }
}
