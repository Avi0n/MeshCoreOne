package one.meshcore.shared.ui

import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactSession
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.meshcore.DiscoverResponse
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.navigation.selectTab
import one.meshcore.shared.navigation.selectTool
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_discovery_add
import one.meshcore.shared.resources.contacts_discovery_added
import one.meshcore.shared.resources.tools_node_discovery
import one.meshcore.shared.resources.tools_node_discovery_repeaters
import one.meshcore.shared.resources.tools_node_discovery_scan_button
import one.meshcore.shared.resources.tools_node_discovery_stop_button
import one.meshcore.shared.tools.NodeDiscoveryViewModel
import one.meshcore.shared.tools.NodeDiscoveryWorkspace
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeDiscoveryLifetimeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private var room: NodeRemovalFixture? = null
    private val sendResult = CompletableDeferred<Long>()
    private val events = MutableSharedFlow<MeshEvent>()
    private var sendCalls = 0
    private var sendCancelled = false
    private val addResult = CompletableDeferred<Unit>()
    private var addCalls = 0
    private var clock = 0L
    private lateinit var workspace: NodeDiscoveryWorkspace
    private lateinit var shell: AdaptiveShellFixture
    private lateinit var scanLabel: String
    private lateinit var stopLabel: String
    private lateinit var addLabel: String
    private lateinit var addedLabel: String
    private lateinit var discoveryLabel: String

    @After
    fun tearDown() {
        scope.cancel()
        room?.close()
    }

    @Test
    fun hidingDiscoveryWhileItsSendIsPendingKeepsTheSameScanOnReturn() {
        show()
        compose.onNode(hasText(scanLabel) and hasClickAction()).performClick()
        compose.waitUntil(TIMEOUT_MS) { sendCalls == 1 }
        tapTab(AppTab.NODES)
        compose.runOnIdle { assertFalse("Hiding Discovery cancelled its pending send", sendCancelled) }
        tapTab(AppTab.TOOLS)
        compose.onNodeWithText(stopLabel).assertExists()
        compose.runOnIdle {
            assertEquals(1, sendCalls)
            sendResult.complete(TAG)
        }
        compose.onNodeWithText(stopLabel).performClick()
        compose.onNode(hasText(scanLabel) and hasClickAction()).assertExists()
    }

    @Test
    fun resizingWhileItsSendIsPendingKeepsTheScanAndItsResults() {
        show()
        compose.onNode(hasText(scanLabel) and hasClickAction()).performClick()
        compose.waitUntil(TIMEOUT_MS) { sendCalls == 1 }
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.WIDE_WIDTH }
        compose.onNodeWithText(stopLabel).assertExists()
        compose.runOnIdle {
            assertFalse(sendCancelled)
            sendResult.complete(TAG)
        }
        compose.waitUntil(TIMEOUT_MS) { events.subscriptionCount.value > 0 }
        emitResponse()
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.COMPACT_WIDTH }
        compose.onNodeWithText(KEY_HEX).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, sendCalls) }
    }

    @Test
    fun returningToTheHubAndReopeningKeepsTheOriginalPendingSend() {
        show()
        compose.onNode(hasText(scanLabel) and hasClickAction()).performClick()
        compose.waitUntil(TIMEOUT_MS) { sendCalls == 1 }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNode(hasText(discoveryLabel) and hasClickAction()).performClick()
        compose.onNodeWithText(stopLabel).assertExists()
        compose.runOnIdle {
            assertFalse(sendCancelled)
            assertEquals(1, sendCalls)
        }
    }

    @Test
    fun responsesArrivingWhileHiddenAppearOnReturnWithoutASecondSend() {
        show()
        startAcknowledgedScan()
        tapTab(AppTab.NODES)
        emitResponse()
        tapTab(AppTab.TOOLS)
        compose.onNodeWithText(KEY_HEX).assertIsDisplayed()
        compose.onNodeWithText(stopLabel).assertExists()
        compose.runOnIdle { assertEquals(1, sendCalls) }
        capturePayloadFrame("discovery-hidden-response.png")
    }

    @Test
    fun aPendingAddPersistsAndShowsItsBadgeAfterLeavingTheTool() {
        show()
        startAcknowledgedScan()
        emitResponse()
        compose.onNodeWithText(addLabel).performClick()
        compose.waitUntil(TIMEOUT_MS) { addCalls == 1 }
        tapTab(AppTab.NODES)
        compose.runOnIdle { addResult.complete(Unit) }
        val fixture = requireNotNull(room)
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { fixture.store.fetchContact(fixture.contact.radioID, KEY) != null }
        }
        tapTab(AppTab.TOOLS)
        compose.onNodeWithText(addedLabel).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, addCalls)
            assertEquals(1, sendCalls)
        }
        capturePayloadFrame("discovery-hidden-add.png")
    }

    @Test
    fun aHiddenDeadlineExpiresWithoutRestartingTheRequestOnReturn() {
        show()
        startAcknowledgedScan()
        tapTab(AppTab.NODES)
        compose.runOnIdle {
            clock = NodeDiscoveryViewModel.DEFAULT_SCAN_DURATION_MS
            workspace.expireDeadline()
        }
        tapTab(AppTab.TOOLS)
        compose.onNode(hasText(scanLabel) and hasClickAction()).assertExists()
        compose.runOnIdle { assertEquals(1, sendCalls) }
    }

    @Test
    fun retiringAHiddenWorkspaceClearsItsResultsAndCancelsThePendingAdd() {
        show()
        startAcknowledgedScan()
        emitResponse()
        compose.onNodeWithText(addLabel).performClick()
        compose.waitUntil(TIMEOUT_MS) { addCalls == 1 }
        tapTab(AppTab.NODES)
        compose.runOnIdle {
            workspace.reset()
            addResult.complete(Unit)
        }
        tapTab(AppTab.TOOLS)
        compose.onNodeWithText(KEY_HEX).assertDoesNotExist()
        compose.onNode(hasText(scanLabel) and hasClickAction()).assertExists()
        val fixture = requireNotNull(room)
        assertFalse(runBlocking { fixture.store.fetchContact(fixture.contact.radioID, KEY) != null })
    }

    private fun startAcknowledgedScan() {
        compose.runOnIdle { sendResult.complete(TAG) }
        compose.onNode(hasText(scanLabel) and hasClickAction()).performClick()
        compose.waitUntil(TIMEOUT_MS) { events.subscriptionCount.value > 0 }
    }

    private fun emitResponse() {
        compose.runOnIdle {
            scope.launch {
                events.emit(
                    MeshEvent.DiscoverResponseReceived(
                        DiscoverResponse(
                            nodeType = ContactType.REPEATER.raw,
                            snr = 5.0,
                            snrIn = 4.0,
                            rssi = -90,
                            pathLength = 1,
                            publicKey = KEY,
                            tag = NodeDiscoveryViewModel.tagToLittleEndianBytes(TAG),
                        ),
                    ),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun show() {
        val room = NodeRemovalFixture(InstrumentationRegistry.getInstrumentation().targetContext, navigation)
        this.room = room
        val base = room.dependencies
        val service = ContactService(
            session = object : ContactSession by NoOpContactSession() {
                override suspend fun addContact(contact: MeshContact) {
                    addCalls++
                    addResult.await()
                }
            },
            store = room.store,
            now = { 0L },
        )
        val sendDiscover: suspend (Int, Boolean) -> Long = { _, _ ->
            sendCalls++
            try {
                sendResult.await()
            } catch (cancelled: CancellationException) {
                sendCancelled = true
                throw cancelled
            }
        }
        workspace = NodeDiscoveryWorkspace(
            scope = scope,
            dependencies = NodeDiscoveryWorkspace.Dependencies(
                sendDiscover = sendDiscover,
                events = { events },
                contactService = { service },
                discoveredNodeStore = base.discoveredNodeStore,
                radioId = base.currentRadioId,
                isRadioConnected = { true },
                maxContacts = { null },
            ),
            nowEpochMillis = { clock },
        )
        val deps = ShellDependencies(
            session = base.session.copy(contactService = { service }),
            services = base.services.copy(
                sendNodeDiscover = sendDiscover,
                meshEvents = { events },
                nodeDiscoveryWorkspace = workspace,
            ),
            sharing = base.sharing,
            preferences = base.preferences,
            radio = base.radio,
            maintenance = base.maintenance,
        )
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            navigation.selectTab(AppTab.TOOLS)
            navigation.selectTool(ToolSelection.NODE_DISCOVERY)
        }
        shell = AdaptiveShellFixture(navigation, compose.activity.resources.displayMetrics.widthPixels, deps)
        compose.setContent {
            scanLabel = formattedStringResource(
                Res.string.tools_node_discovery_scan_button,
                stringResource(Res.string.tools_node_discovery_repeaters),
            )
            stopLabel = stringResource(Res.string.tools_node_discovery_stop_button)
            addLabel = stringResource(Res.string.contacts_discovery_add)
            addedLabel = stringResource(Res.string.contacts_discovery_added)
            discoveryLabel = stringResource(Res.string.tools_node_discovery)
            shell.Content()
        }
    }

    private fun tapTab(tab: AppTab) {
        compose.onNode(hasText(shell.tabLabels.getValue(tab)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TAG = 0x1122_3344L
        const val TIMEOUT_MS = 5_000L
        val KEY = ByteArray(32) { 0x44 }
        val KEY_HEX = "44".repeat(32)
    }
}
