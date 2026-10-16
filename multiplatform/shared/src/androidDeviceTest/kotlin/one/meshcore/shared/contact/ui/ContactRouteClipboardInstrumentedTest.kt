package one.meshcore.shared.contact.ui

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_copy_route
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactRouteClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val contact = mutableStateOf(ContactClipboardFixtures.contact)
    private val clipboard = HoldingClipboard()
    private val feedback = mutableListOf<HapticFeedbackType>()
    private val otherActions = mutableListOf<String>()
    private lateinit var copyLabel: String
    private lateinit var composeView: View

    @After
    fun releaseWrite() {
        clipboard.release.complete(Unit)
    }

    @Test
    fun copyButtonUsesRawMultibyteRouteWithoutOtherActions() {
        showRoute()
        copyRoute()
        assertPendingRoute()
        releaseAndAssertComplete()
    }

    @Test
    fun nativeAccessibilityCopyUsesTheSameRawRoute() {
        showRoute()
        val route = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).performScrollTo()
            .fetchSemanticsNode()
        compose.runOnIdle {
            val provider = requireNotNull(composeView.accessibilityNodeProvider)
            val info = requireNotNull(provider.createAccessibilityNodeInfo(route.id))
            val action = info.actionList.single { it.label?.toString() == copyLabel }
            assertTrue(provider.performAction(route.id, action.id, null))
        }
        assertPendingRoute()
        releaseAndAssertComplete()
    }

    @Test
    fun hidingRouteControlsPreservesSectionOwnedCopy() {
        showRoute()
        copyRoute()
        assertPendingRoute()
        compose.runOnIdle { contact.value = DIRECT_CONTACT }
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(0, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
        }
        releaseAndAssertComplete()
    }

    @Test
    fun removingSectionCancelsPendingRouteCopy() {
        showRoute()
        copyRoute()
        assertPendingRoute()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            assertTrue(otherActions.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            assertTrue(otherActions.isEmpty())
        }
    }

    @Test
    fun directAndFloodRoutesHaveNoCopyControls() {
        showRoute(DIRECT_CONTACT)
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertCountEquals(0)
        compose.runOnIdle { contact.value = DIRECT_CONTACT.copy(outPathLength = FLOOD_PATH) }
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue(clipboard.entries.isEmpty())
            assertTrue(feedback.isEmpty())
            assertTrue(otherActions.isEmpty())
        }
    }

    private fun copyRoute() {
        compose.onNodeWithText(copyLabel).performScrollTo().performClick()
    }

    private fun assertPendingRoute() {
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(
                ContactClipboardFixtures.COPIED_ROUTE,
                requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString(),
            )
            assertEquals(0, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            assertTrue(otherActions.isEmpty())
        }
    }

    private fun releaseAndAssertComplete() {
        compose.runOnIdle { clipboard.release.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.completed)
            assertEquals(0, clipboard.cancelled)
            assertTrue(feedback.isEmpty())
            assertTrue(otherActions.isEmpty())
        }
    }

    private fun showRoute(initial: ContactDto = ContactClipboardFixtures.contact) {
        contact.value = initial
        compose.setContent {
            copyLabel = stringResource(Res.string.contacts_detail_copy_route)
            composeView = LocalView.current
            CompositionLocalProvider(
                LocalClipboard provides clipboard,
                LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        feedback += hapticFeedbackType
                    }
                },
            ) {
                MaterialTheme {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        if (visible.value) {
                            ContactDetailPathSection(
                                contact = contact.value,
                                resolveHopName = { if (it.first() == FIRST_HOP) "First hop" else "Second hop" },
                                isDiscoveringPath = false,
                                discoverySecondsRemaining = null,
                                onCancelDiscovery = null,
                                enablement = ContactClipboardFixtures.disabledActions,
                                onDiscoverPath = { otherActions += "discover" },
                                onResetPath = { otherActions += "reset" },
                                onEditPath = { otherActions += "edit" },
                                radioHint = "Offline",
                                pathStatus = null,
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val FLOOD_PATH = 0xFF
        val FIRST_HOP = 0xA3.toByte()
        val DIRECT_CONTACT = ContactClipboardFixtures.contact.copy(outPathLength = 0, outPath = ByteArray(0))
    }
}
