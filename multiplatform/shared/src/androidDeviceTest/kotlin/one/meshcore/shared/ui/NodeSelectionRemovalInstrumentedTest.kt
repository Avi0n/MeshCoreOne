package one.meshcore.shared.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.navigateToContactDetail
import one.meshcore.shared.navigation.setSidebarWide
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_delete
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeSelectionRemovalInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private var fixture: NodeRemovalFixture? = null
    private lateinit var deleteLabel: String

    @After
    fun tearDown() {
        scope.cancel()
        fixture?.close()
    }

    @Test
    fun confirmedListDeletionClosesItsSelectedNodeDetail() {
        val fixture = showFixture()
        deleteRow()
        compose.waitUntil(TIMEOUT_MS) { runBlocking { fixture.store.fetchContact(fixture.contact.id) } == null }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, fixture.removeCalls)
            assertNull(navigation.selectedContactId.value)
        }
        compose.onNodeWithText(DETAIL).assertDoesNotExist()
        capturePayloadFrame("node-removal-root.png")
    }

    @Test
    fun failedListDeletionKeepsItsSelectedNodeDetail() {
        val fixture = showFixture()
        fixture.removalFailure = IllegalStateException("fixture radio failed")
        deleteRow()
        compose.waitUntil(TIMEOUT_MS) { fixture.removeCalls == 1 }
        compose.waitForIdle()
        assertNotNull(runBlocking { fixture.store.fetchContact(fixture.contact.id) })
        compose.runOnIdle { assertEquals(fixture.contact.id.value, navigation.selectedContactId.value) }
        compose.onNodeWithText(DETAIL).assertIsDisplayed()
    }

    @Test
    fun hidingTheRowWithSearchKeepsItsSelectedNodeDetail() {
        val fixture = showFixture()
        compose.onNode(hasSetTextAction()).performTextReplacement("no matching fixture")
        compose.onNodeWithText(NodeRemovalFixture.NAME).assertDoesNotExist()
        compose.runOnIdle { assertEquals(fixture.contact.id.value, navigation.selectedContactId.value) }
        compose.onNodeWithText(DETAIL).assertIsDisplayed()
    }

    private fun showFixture(): NodeRemovalFixture {
        val fixture = NodeRemovalFixture(InstrumentationRegistry.getInstrumentation().targetContext, navigation)
        this.fixture = fixture
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            navigation.setSidebarWide(true)
            navigation.navigateToContactDetail(fixture.contact.id.value)
        }
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    deleteLabel = stringResource(Res.string.contacts_common_delete)
                    val native = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(native.density * WIDE_SCALE, native.fontScale),
                    ) {
                        WideShell(
                            coordinator = navigation,
                            unreadCount = 0,
                            nodesContent = {
                                ShellNodesTab(fixture.dependencies, deviceLocation = { null }, onSheet = {})
                            },
                            contactDetailContent = { _, _ -> Text(DETAIL) },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(DETAIL).assertIsDisplayed()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(NodeRemovalFixture.NAME))
                .fetchSemanticsNodes().isNotEmpty()
        }
        return fixture
    }

    private fun deleteRow() {
        compose.onNodeWithText(NodeRemovalFixture.NAME).performTouchInput { longClick() }
        compose.onNode(hasText(deleteLabel) and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val WIDE_SCALE = 0.5f
        const val DETAIL = "Selected node detail"
    }
}
