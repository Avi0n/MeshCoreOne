package one.meshcore.shared.settings.ui

import android.graphics.Bitmap
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.BatteryOcvGrid
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.battery.OcvEditorState
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.StatusPillState
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.navigation.selectSetting
import one.meshcore.shared.navigation.selectTab
import one.meshcore.shared.onboarding.OnboardingViewModel
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_battery_curve_accessibility_voltage_label
import one.meshcore.shared.resources.settings_battery_curve_custom
import one.meshcore.shared.resources.settings_battery_curve_m_v
import one.meshcore.shared.settings.NodesAutoAddSnapshot
import one.meshcore.shared.ui.AppShell
import one.meshcore.shared.ui.InertBleScanController
import one.meshcore.shared.ui.InertConnectionActions
import one.meshcore.shared.ui.InertPermissionController
import one.meshcore.shared.ui.ShellDependencies
import one.meshcore.shared.ui.ShellMaintenanceInputs
import one.meshcore.shared.ui.ShellPreferenceInputs
import one.meshcore.shared.ui.ShellRadioInputs
import one.meshcore.shared.ui.ShellServiceInputs
import one.meshcore.shared.ui.ShellSessionInputs
import one.meshcore.shared.ui.ShellSettingsTab
import one.meshcore.shared.ui.ShellSharingInputs
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.tabLabel
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.math.abs

internal class BatteryCurveSettingsParentFixture(
    private val compose: ComposeContentTestRule,
    private val activity: ComponentActivity,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private var mounted by mutableStateOf(true)
    private lateinit var view: View
    private val previousSoftInputMode = activity.window.attributes.softInputMode
    private var labels = emptyList<String>()
    private var unitLabel = ""
    private var valueHorizontalPadding = 0f
    var settingsTabLabel = ""
        private set
    var customLabel = ""
        private set
    var fontScale by mutableStateOf<Float?>(null)
    var snapshot by mutableStateOf(OcvEditorState(OCVPreset.LI_ION, OCVPreset.LI_ION.ocvArray))
        private set
    var holdSave = false
    val saveRelease = CompletableDeferred<Unit>()
    val requests = mutableStateListOf<BatteryParentSave>()

    fun mount() {
        val deps = dependencies()
        compose.runOnUiThread {
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            navigation.selectTab(AppTab.SETTINGS)
            navigation.selectSetting(SettingsDestination.ADVANCED)
        }
        compose.setContent {
            view = LocalView.current
            val native = LocalDensity.current
            labels = List(OCVPreset.POINT_COUNT) {
                formattedStringResource(
                    Res.string.settings_battery_curve_accessibility_voltage_label,
                    BatteryOcvGrid.percentAtIndex(it),
                )
            }
            unitLabel = stringResource(Res.string.settings_battery_curve_m_v)
            settingsTabLabel = tabLabel(AppTab.SETTINGS)
            customLabel = stringResource(Res.string.settings_battery_curve_custom)
            CompositionLocalProvider(LocalDensity provides Density(native.density, fontScale ?: native.fontScale)) {
                val contentPadding = OutlinedTextFieldDefaults.contentPaddingWithoutLabel()
                val direction = LocalLayoutDirection.current
                valueHorizontalPadding = with(LocalDensity.current) {
                    (
                        contentPadding.calculateLeftPadding(direction) +
                            contentPadding.calculateRightPadding(direction)
                        ).toPx()
                }
                if (mounted) {
                    AppShell(
                        coordinator = navigation,
                        modifier = Modifier.fillMaxSize().testTag(ROOT_TAG),
                        settingsContent = {
                            ShellSettingsTab(deps, DeviceConnectionState.READY, networkOffline = true, onSheet = {})
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun dependencies() = ShellDependencies(
        session = ShellSessionInputs(
            coordinator = navigation,
            onboarding = OnboardingViewModel(InMemoryPreferencesStore()),
            initialOnboardingComplete = true,
            statusPill = MutableStateFlow(StatusPillState.Hidden),
            battery = MutableStateFlow(null),
            connectionState = MutableStateFlow(DeviceConnectionState.READY),
            connectionAlert = MutableStateFlow(null),
            unreadCount = MutableStateFlow(0),
            localNodeName = MutableStateFlow(null),
            permissions = InertPermissionController(),
            bleScan = InertBleScanController(),
            actions = InertConnectionActions(),
        ),
        services = ShellServiceInputs(),
        sharing = ShellSharingInputs(),
        preferences = ShellPreferenceInputs(),
        radio = ShellRadioInputs(
            settingsStartupReadsAllowed = MutableStateFlow(false),
            refreshDeviceSettings = {},
            nodesAutoAddSnapshot = { NodesAutoAddSnapshot(false, 0, 0, FIRMWARE_VERSION) },
            deviceOcvSnapshot = { snapshot },
            updateDeviceOcv = { preset, values ->
                assertNull(BatteryOcv.validationErrorKey(values))
                requests += BatteryParentSave(preset, values.toList())
                if (holdSave) saveRelease.await()
                snapshot = OcvEditorState(preset, values.toList())
            },
        ),
        maintenance = ShellMaintenanceInputs(),
    )

    fun fields() = compose.onAllNodes(voltageMatcher(), useUnmergedTree = true)

    fun field(index: Int) = compose.onNode(voltageMatcher(index), useUnmergedTree = true)

    private fun voltageMatcher(index: Int? = null) = SemanticsMatcher("Generated OCV voltage field") { node ->
        node.config.contains(SemanticsProperties.EditableText) &&
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { description ->
                (index?.let { listOf(labels[it]) } ?: labels).any { description.startsWith("$it.") }
            }
    }

    fun openKeyboard() {
        compose.runOnUiThread { keyboard().show(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(TIMEOUT_MS) {
            nativeInsets().isVisible(WindowInsetsCompat.Type.ime()) &&
                nativeInsets().getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
        }
        compose.waitForIdle()
    }

    fun hideKeyboard() {
        compose.runOnUiThread { keyboard().hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(TIMEOUT_MS) { !nativeInsets().isVisible(WindowInsetsCompat.Type.ime()) }
        compose.waitForIdle()
    }

    fun settleGeometry(index: Int): BatteryParentGeometry {
        var last: BatteryParentGeometry? = null
        var stable = 0
        compose.waitUntil(TIMEOUT_MS) {
            val current = geometry(index)
            stable = if (current == last) stable + 1 else 0
            last = current
            stable >= STABLE_SAMPLES
        }
        return requireNotNull(last)
    }

    private fun geometry(index: Int): BatteryParentGeometry {
        val node = field(index).fetchSemanticsNode()
        val field = fullRect(node)
        val scroll = generateSequence(node.parent) {
            it.parent
        }.first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        val percent = nearestText("${BatteryOcvGrid.percentAtIndex(index)}%", field)
        val unit = nearestText(unitLabel, field)
        var result: BatteryParentGeometry? = null
        compose.runOnUiThread {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout =
                requireNotNull(layouts.singleOrNull()) { "Production voltage field exposes one native text layout" }
            val value = node.config[SemanticsProperties.EditableText].text
            val insets = nativeInsets()
            val window = activity.windowManager.currentWindowMetrics.bounds
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            result = BatteryParentGeometry(
                index, field, percent, unit, fullRect(scroll), node.boundsInWindow,
                Rect(window.left.toFloat(), window.top.toFloat(), window.right.toFloat(), window.bottom.toFloat()),
                ime, navigationBar, insets.isVisible(WindowInsetsCompat.Type.ime()),
                node.config.getOrNull(SemanticsProperties.Focused) == true,
                scroll.config[SemanticsProperties.VerticalScrollAxisRange].value(),
                view.height, view.rootView.height, IntOffset(origin[0], origin[1]),
                view.scrollY, view.rootView.scrollY, activity.window.attributes.softInputMode,
                value,
                layout.getLineRight(0) - layout.getLineLeft(0),
                layout.multiParagraph.height,
                valueHorizontalPadding,
                layout.layoutInput.text.text == value &&
                    layout.lineCount == 1 &&
                    !layout.isLineEllipsized(0) &&
                    !layout.multiParagraph.didExceedMaxLines &&
                    layout.getLineEnd(0, visibleEnd = true) == value.length,
            )
        }
        return requireNotNull(result)
    }

    private fun nearestText(text: String, field: Rect): Rect = compose.onAllNodes(hasText(text), useUnmergedTree = true)
        .fetchSemanticsNodes().map(::fullRect).minBy { abs(it.center.y - field.center.y) }

    private fun fullRect(node: SemanticsNode): Rect {
        val position = node.positionOnScreen
        return Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
    }

    fun exportState(name: String, index: Int, settled: BatteryParentGeometry? = null) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val stem = "battery-parent-$name"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(NATIVE_IDLE_MS, TIMEOUT_MS)
        compose.waitForIdle()
        val measured = runCatching { settleGeometry(index) }
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(directory, "$stem.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it))
        }
        bitmap.recycle()
        File(directory, "$stem.txt").writeText(
            "fontScale=$fontScale snapshot=$snapshot requests=${requests.toList()} " +
                "geometry=$measured previousGeometry=$settled " +
                "capture=settled-before-screenshot-not-atomic\n" +
                compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE),
        )
        writeBatteryParentNativeHierarchy(automation, File(directory, "$stem.xml"))
    }

    private fun nativeInsets() = requireNotNull(ViewCompat.getRootWindowInsets(view))

    private fun keyboard() = WindowInsetsControllerCompat(activity.window, view)

    fun close() {
        try {
            compose.runOnUiThread { if (::view.isInitialized) keyboard().hide(WindowInsetsCompat.Type.ime()) }
            compose.runOnIdle {
                saveRelease.complete(Unit)
                mounted = false
            }
            compose.waitForIdle()
        } finally {
            compose.runOnUiThread { activity.window.setSoftInputMode(previousSoftInputMode) }
            scope.cancel()
        }
    }

    private companion object {
        const val ROOT_TAG = "battery-settings-parent"
        const val FIRMWARE_VERSION = "1.14.0"
        const val TIMEOUT_MS = 5_000L
        const val NATIVE_IDLE_MS = 100L
        const val STABLE_SAMPLES = 3
        const val PNG_QUALITY = 100
    }
}
