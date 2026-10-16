package one.meshcore.shared.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.navigation.selectTab
import one.meshcore.shared.navigation.selectTool
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_noise_floor_d_bm
import one.meshcore.shared.resources.tools_noise_floor_quality_excellent
import one.meshcore.shared.tools.NOISE_FLOOR_POLL_INTERVAL_MS
import one.meshcore.shared.tools.NoiseFloorReading
import one.meshcore.shared.tools.NoiseFloorViewModel
import one.meshcore.shared.tools.currentReadingAccessibilityLabel
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoiseFloorLifetimeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private val pending = CompletableDeferred<NoiseFloorReading>()
    private var room: NodeRemovalFixture? = null

    @Volatile private var calls = 0
    private lateinit var shell: AdaptiveShellFixture
    private lateinit var readingLabel: String
    private lateinit var owner: MutableSharedFlow<MeshEvent>
    private var radio: Uuid? = null

    @Volatile private var activeFetches = 0

    @Volatile private var cancelledFetches = 0

    @After
    fun tearDown() {
        pending.cancel()
        scope.cancel()
        room?.close()
    }

    @Test
    fun tabReturnKeepsItsReadingWithoutPollingWhileHidden() {
        show()
        compose.waitUntil(TIMEOUT_MS) { calls == 2 }
        tapTab(AppTab.CHATS)
        waitWhileHidden()
        compose.runOnIdle { assertEquals(2, calls) }
        tapTab(AppTab.TOOLS)
        waitForReading()
        compose.onNodeWithContentDescription(readingLabel).assertIsDisplayed()
    }

    @Test
    fun widthRemountRetainsSeriesAndOnePoller() {
        show(samples = 2)
        compose.waitUntil(TIMEOUT_MS) { calls == 3 }
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.WIDE_WIDTH }
        waitForReading()
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.COMPACT_WIDTH }
        waitForReading()
        compose.runOnIdle {
            assertEquals(2, model.state.value.readings.size)
            assertEquals(1, activeFetches)
        }
        capturePayloadFrame("noise-floor-retained-chart.png")
    }

    @Test
    fun activityBackgroundPausesPollingAndRetainsSeries() {
        show()
        compose.waitUntil(TIMEOUT_MS) { calls == 2 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(NOISE_FLOOR_POLL_INTERVAL_MS * 2)
        assertEquals(2, calls)
        assertEquals(1, model.state.value.readings.size)
        assertEquals(0, activeFetches)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForReading()
        compose.runOnIdle { assertEquals(1, model.state.value.readings.size) }
    }

    @Test
    fun nativeBackAndReopenRetainReading() {
        show()
        compose.waitUntil(TIMEOUT_MS) { calls == 2 }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        waitWhileHidden()
        compose.runOnIdle {
            assertEquals(2, calls)
            navigation.selectTool(ToolSelection.NOISE_FLOOR)
        }
        waitForReading()
    }

    @Test
    fun mountedSameRadioReplacementCancelsOldFetchAndKeepsHistory() {
        show()
        compose.waitUntil(TIMEOUT_MS) { calls == 2 }
        replaceGraph(changeRadio = false)
        compose.waitUntil(TIMEOUT_MS) { cancelledFetches >= 1 && calls >= 3 }
        waitForReading()
        compose.runOnIdle {
            assertEquals(1, activeFetches)
            assertEquals(1, model.state.value.readings.size)
        }
    }

    @Test
    fun mountedNewRadioClearsOldSeriesBeforeReading() {
        show()
        compose.waitUntil(TIMEOUT_MS) { calls == 2 }
        val oldSample = model.state.value.readings.single().id
        replaceGraph(changeRadio = true)
        compose.waitUntil(TIMEOUT_MS) { cancelledFetches >= 1 && calls >= 3 }
        compose.runOnIdle { assertEquals(0, model.state.value.readings.size) }
        compose.runOnIdle { pending.complete(reading()) }
        waitForReading()
        compose.runOnIdle {
            assertEquals(1, model.state.value.readings.size)
            org.junit.Assert.assertNotEquals(oldSample, model.state.value.readings.single().id)
        }
    }

    private fun replaceGraph(changeRadio: Boolean) {
        compose.runOnIdle {
            owner = MutableSharedFlow()
            if (changeRadio) radio = Uuid.random()
            val base = requireNotNull(shell.dependencies)
            shell.dependencies = ShellDependencies(
                base.session,
                base.services.copy(meshEvents = { owner }, currentRadioId = { radio }),
                base.sharing,
                base.preferences,
                base.radio,
                base.maintenance,
            )
        }
    }

    private lateinit var model: NoiseFloorViewModel

    private fun show(samples: Int = 1) {
        val room = NodeRemovalFixture(compose.activity, navigation).also { this.room = it }
        val base = room.dependencies
        owner = MutableSharedFlow()
        radio = base.services.currentRadioId()
        val fetch: suspend () -> NoiseFloorReading? = {
            calls++
            activeFetches++
            try {
                if (calls <= samples) reading() else pending.await()
            } catch (cancelled: CancellationException) {
                cancelledFetches++
                throw cancelled
            } finally {
                activeFetches--
            }
        }
        model = NoiseFloorViewModel(
            scope,
            NoiseFloorViewModel.Dependencies(
                fetchReading = { fetch },
                sessionOwner = { owner },
                radioId = { radio },
            ),
        )
        val dependencies = ShellDependencies(
            session = base.session,
            services = base.services.copy(
                fetchNoiseFloorReading = fetch,
                noiseFloorWorkspace = model,
                currentRadioId = { radio },
                meshEvents = { owner },
            ),
            sharing = base.sharing,
            preferences = base.preferences,
            radio = base.radio,
            maintenance = base.maintenance,
        )
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.runOnIdle {
            navigation.selectTab(AppTab.TOOLS)
            navigation.selectTool(ToolSelection.NOISE_FLOOR)
        }
        shell = AdaptiveShellFixture(navigation, compose.activity.resources.displayMetrics.widthPixels, dependencies)
        compose.setContent {
            readingLabel = currentReadingAccessibilityLabel(
                SAMPLE_DBM,
                stringResource(Res.string.tools_noise_floor_d_bm),
                stringResource(Res.string.tools_noise_floor_quality_excellent),
                "",
            )
            MaterialTheme { shell.Content() }
        }
        waitForReading()
    }

    private fun waitWhileHidden() {
        val hiddenAt = SystemClock.elapsedRealtime()
        compose.waitUntil(TIMEOUT_MS) {
            SystemClock.elapsedRealtime() - hiddenAt >= NOISE_FLOOR_POLL_INTERVAL_MS * 2
        }
    }

    private fun waitForReading() {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription(readingLabel).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tapTab(tab: AppTab) {
        compose.onNode(hasText(shell.tabLabels.getValue(tab)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun reading() = NoiseFloorReading(Uuid.random(), System.currentTimeMillis(), SAMPLE_DBM, -70, 5.0)

    private companion object {
        const val SAMPLE_DBM = -101
        const val TIMEOUT_MS = 10_000L
    }
}
