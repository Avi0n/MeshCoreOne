package one.meshcore.shared.ui

import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.navigation.selectTab
import one.meshcore.shared.navigation.selectTool
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_path_edit_add_hop
import one.meshcore.shared.resources.contacts_results_title
import one.meshcore.shared.resources.contacts_trace_error_all_failed
import one.meshcore.shared.resources.contacts_trace_failed
import one.meshcore.shared.resources.contacts_trace_list_batch_trace
import one.meshcore.shared.resources.contacts_trace_list_empty_path
import one.meshcore.shared.resources.contacts_trace_list_run_trace
import one.meshcore.shared.resources.contacts_trace_list_running_trace
import one.meshcore.shared.resources.tools_saved_paths_load_failed
import one.meshcore.shared.tools.TracePathViewModel
import one.meshcore.shared.tools.TracePathWorkspace
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TraceResult
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TracePathLifetimeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private var room: NodeRemovalFixture? = null
    private val response = CompletableDeferred<TraceResult>()
    private val laterResponse = CompletableDeferred<TraceResult>()
    private var pathBytes = byteArrayOf()
    private var runCalls = 0
    private var runCancelled = false
    private lateinit var shell: AdaptiveShellFixture
    private lateinit var addHopLabel: String
    private lateinit var runLabel: String
    private lateinit var runningLabel: String
    private lateinit var resultsLabel: String
    private lateinit var cancelLabel: String
    private lateinit var batchLabel: String
    private lateinit var model: TracePathViewModel

    @After
    fun tearDown() {
        response.cancel()
        laterResponse.cancel()
        runBlocking { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
        room?.close()
    }

    @Test
    fun hidingTheToolKeepsItsPendingTraceAndPathOnReturn() {
        startTrace()
        tapTab(AppTab.NODES)
        compose.runOnIdle { assertFalse("Hiding Trace Path cancelled its pending run", runCancelled) }
        tapTab(AppTab.TOOLS)
        compose.onNodeWithText(runningLabel).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, runCalls) }
        capturePayloadFrame("trace-retained-pending.png")
    }

    @Test
    fun hiddenCompletionPresentsItsStoredResultOnReturn() {
        startTrace()
        tapTab(AppTab.NODES)
        completeFirst()
        tapTab(AppTab.TOOLS)
        compose.onNodeWithText(resultsLabel).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText(runLabel).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, runCalls) }
    }

    @Test
    fun hidingKeepsTheBatchRunningToCompletion() {
        startTrace(batch = true)
        tapTab(AppTab.NODES)
        completeFirst()
        compose.runOnIdle { laterResponse.complete(success()) }
        compose.waitUntil(TIMEOUT_MS) {
            model.state.value.batchResults.size == BATCH_COUNT && !model.state.value.isRunning
        }
        tapTab(AppTab.TOOLS)
        compose.onNodeWithText(resultsLabel).assertIsDisplayed()
        compose.runOnIdle { assertFalse(runCancelled) }
    }

    @Test
    fun visibleResultDismissalCancelsTheRemainingBatch() {
        startTrace(batch = true)
        completeFirst()
        compose.onNodeWithText(resultsLabel).assertIsDisplayed()
        compose.waitUntil(TIMEOUT_MS) { runCalls == 2 }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(TIMEOUT_MS) { runCancelled }
        compose.onNodeWithText(runLabel).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, runCalls)
            assertEquals(1, model.state.value.batchResults.size)
        }
    }

    @Test
    fun widthRemountKeepsPendingWorkAndExplicitCancelStopsIt() {
        startTrace()
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.WIDE_WIDTH }
        compose.onNodeWithText(runningLabel).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.COMPACT_WIDTH }
        compose.onNodeWithText(cancelLabel).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { runCancelled }
        compose.onNodeWithText(runLabel).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, runCalls) }
    }

    private fun startTrace(batch: Boolean = false) {
        show()
        compose.onNode(hasText(addHopLabel) and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(NodeRemovalFixture.NAME).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(NodeRemovalFixture.NAME).performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        if (batch) compose.onNodeWithText(batchLabel).performScrollTo().performClick()
        if (batch) {
            compose.onNode(hasText(runLabel, substring = true) and hasClickAction()).performScrollTo().performClick()
        } else {
            compose.onNodeWithText(runLabel).performScrollTo().performClick()
        }
        compose.waitUntil(TIMEOUT_MS) { runCalls == 1 }
    }
    private fun completeFirst() {
        compose.runOnIdle { response.complete(success()) }
        compose.waitForIdle()
    }
    private fun success() = TraceResult(emptyList(), 10, true, null, pathBytes, 1)

    private fun show() {
        val room = NodeRemovalFixture(InstrumentationRegistry.getInstrumentation().targetContext, navigation)
        this.room = room
        runBlocking { room.store.saveContact(room.contact.copy(typeRawValue = ContactType.REPEATER.raw)) }
        val base = room.dependencies
        val runner: suspend (ByteArray, Int, Int) -> TraceResult = { path, _, _ ->
            pathBytes = path
            runCalls++
            try {
                if (runCalls == 1) response.await() else laterResponse.await()
            } catch (cancelled: CancellationException) {
                runCancelled = true
                throw cancelled
            }
        }
        val workspace = TracePathWorkspace(
            scope,
            TracePathWorkspace.Dependencies(
                runner = { runner },
                builder = TracePathBuilder.Dependencies(
                    contactService = base.contactService,
                    radioId = base.currentRadioId,
                ),
                deviceHashMode = { null },
                deviceLocation = { null },
                savedPathStore = base.savedTracePathStore,
                recents = null,
            ),
        )
        val deps = ShellDependencies(
            session = base.session,
            services = base.services.copy(runTracePath = runner, tracePathWorkspace = workspace),
            sharing = base.sharing,
            preferences = base.preferences,
            radio = base.radio,
            maintenance = base.maintenance,
        )
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            navigation.selectTab(AppTab.TOOLS)
            navigation.selectTool(ToolSelection.TRACE_PATH)
        }
        shell = AdaptiveShellFixture(navigation, compose.activity.resources.displayMetrics.widthPixels, deps)
        compose.setContent {
            addHopLabel = stringResource(Res.string.contacts_path_edit_add_hop)
            runLabel = stringResource(Res.string.contacts_trace_list_run_trace)
            runningLabel = stringResource(Res.string.contacts_trace_list_running_trace)
            resultsLabel = stringResource(Res.string.contacts_results_title)
            cancelLabel = stringResource(Res.string.contacts_common_cancel)
            batchLabel = stringResource(Res.string.contacts_trace_list_batch_trace)
            model = workspace.bind(
                TracePathWorkspace.Messages(
                    stringResource(Res.string.contacts_trace_list_empty_path),
                    stringResource(Res.string.contacts_trace_failed),
                    stringResource(Res.string.contacts_trace_error_all_failed),
                    stringResource(Res.string.tools_saved_paths_load_failed),
                ),
            )
            shell.Content()
        }
    }

    private fun tapTab(tab: AppTab) {
        compose.onNode(hasText(requireNotNull(shell.tabLabels[tab])) and hasClickAction()).performClick()
    }

    companion object {
        const val TIMEOUT_MS = 5_000L
        const val BATCH_COUNT = 3
    }
}
