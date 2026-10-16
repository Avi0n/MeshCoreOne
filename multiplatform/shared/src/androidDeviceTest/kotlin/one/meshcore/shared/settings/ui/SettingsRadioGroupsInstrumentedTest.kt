package one.meshcore.shared.settings.ui

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.battery.OcvEditorState
import one.meshcore.shared.channel.RegionDiscoverUiResult
import one.meshcore.shared.contact.StaleNodeRemovalResult
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.settings.NodesAutoAddDraft
import one.meshcore.shared.settings.NodesAutoAddSnapshot
import one.meshcore.shared.theme.AppearancePreferencesStore
import one.meshcore.shared.theme.ThemeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRadioGroupsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var composeView: View

    @Test
    fun appearanceSchemeAndThemesHaveDistinctGroups() {
        compose.setContent {
            composeView = LocalView.current
            val scope = rememberCoroutineScope()
            val store = remember { AppearancePreferencesStore(InMemoryPreferencesStore(), scope) }
            MaterialTheme { AppearanceSettingsScreen(store) }
        }
        assertGroups(listOf(SCHEME_OPTIONS, ThemeRegistry.all.size))
    }

    @Test
    fun mapSchemeChoicesShareOneGroup() {
        compose.setContent {
            composeView = LocalView.current
            val scope = rememberCoroutineScope()
            val store = remember { MapDisplayPreferencesStore(InMemoryPreferencesStore(), scope) }
            MaterialTheme { MapsSettingsScreen(store, onOpenOfflineMaps = {}) }
        }
        assertGroups(listOf(SCHEME_OPTIONS))
    }

    @Test
    fun staleChoicesShareOneGroupAndToggleRemainsIndependent() {
        compose.setContent {
            composeView = LocalView.current
            var threshold by remember { mutableStateOf(STALE_DAYS) }
            MaterialTheme {
                StaleNodeCleanupSection(threshold, true, onThresholdChange = { threshold = it })
            }
        }
        assertGroups(listOf(STALE_OPTIONS))
        val toggles = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
        toggles.assertCountEquals(1).onFirst().performClick()
        compose.onAllNodes(radioRole, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun autoAddModeAndMaximumHopsHaveDistinctGroups() {
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme { AdvancedSettingsScreen(RadioGroupHost()) }
        }
        assertGroups(listOf(SCHEME_OPTIONS, HOP_OPTIONS))
    }

    @Test
    fun batteryCurveFollowsAReplacementDeviceSnapshot() {
        val host = RadioGroupHost(
            initialOcv = OcvEditorState(OCVPreset.LI_ION, OCVPreset.LI_ION.ocvArray),
            includeOcvEditor = true,
        )
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme { AdvancedSettingsScreen(host) }
        }

        compose.onNodeWithText(OCVPreset.LI_ION.displayName).performScrollTo().assertIsSelected()

        compose.runOnIdle {
            host.ocvSnapshot = OcvEditorState(OCVPreset.LI_FE_PO4, OCVPreset.LI_FE_PO4.ocvArray)
        }

        compose.onNodeWithText(OCVPreset.LI_FE_PO4.displayName).performScrollTo().assertIsSelected()
    }

    private fun assertGroups(expectedSizes: List<Int>) {
        val radios = compose.onAllNodes(radioRole, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(expectedSizes.sum(), radios.size)
        val groups = radios.groupBy { radio ->
            val group = radio.parent?.takeIf { it.config.contains(SemanticsProperties.SelectableGroup) }
            assertNotNull("Each radio choice has a selectable-group parent", group)
            requireNotNull(group).id
        }
        assertEquals(expectedSizes.sorted(), groups.values.map { it.size }.sorted())
        groups.forEach { (groupId, choices) ->
            assertEquals(1, choices.count { it.config[SemanticsProperties.Selected] })
            choices.forEachIndexed { index, choice ->
                val row = compose.onNode(SemanticsMatcher("Radio choice ${choice.id}") { it.id == choice.id }, true)
                if (generateSequence(choice.parent) { it.parent }
                        .any { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                ) {
                    row.performScrollTo()
                }
                val visibleChoice = row.fetchSemanticsNode()
                compose.runOnIdle {
                    val provider = requireNotNull(composeView.accessibilityNodeProvider)
                    val collection = requireNotNull(provider.createAccessibilityNodeInfo(groupId)?.collectionInfo)
                    assertEquals(choices.size, collection.rowCount)
                    assertEquals(1, collection.columnCount)
                    val info = requireNotNull(provider.createAccessibilityNodeInfo(visibleChoice.id))
                    val item = requireNotNull(info.collectionItemInfo)
                    assertEquals(index, item.rowIndex)
                    assertEquals(0, item.columnIndex)
                    assertEquals(visibleChoice.config[SemanticsProperties.Selected], item.isSelected)
                }
            }
        }
    }

    private companion object {
        const val SCHEME_OPTIONS = 3
        const val STALE_OPTIONS = 5
        const val HOP_OPTIONS = 8
        const val STALE_DAYS = 7
        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    }
}

private class RadioGroupHost(initialOcv: OcvEditorState? = null, includeOcvEditor: Boolean = false) :
    AdvancedSettingsHost {
    var ocvSnapshot by mutableStateOf(initialOcv)

    override val radioSnapshot = { null }
    override val nodesAutoAddSnapshot = { NodesAutoAddSnapshot(false, 0, 0, "1.14.0") }
    override val applyNodesAutoAdd: suspend (NodesAutoAddDraft) -> Unit = {}
    override val deviceOtherParamsSnapshot = { null }
    override val setTelemetryModes: suspend (Int, Int, Int) -> Unit = { _, _, _ -> }
    override val setMultiAcks: suspend (Int) -> Unit = {}
    override val isDeviceConnected = true
    override val staleCleanupThresholdDays = 0
    override val onStaleCleanupThresholdChange: (Int) -> Unit = {}
    override val lastStaleCleanupSeconds = 0L
    override val nowSeconds = { 0L }
    override val deviceOcvSnapshot = { ocvSnapshot }
    override val updateDeviceOcv: (suspend (OCVPreset, List<Int>) -> Unit)? =
        if (includeOcvEditor) {
            { preset, values -> ocvSnapshot = OcvEditorState(preset, values) }
        } else {
            null
        }
    override val knownRegions = { emptyList<String>() }
    override val defaultFloodScopeName = { null }
    override val applyDefaultFloodScope: suspend (String?) -> Unit = {}
    override val discoverNearbyRegions: suspend () -> RegionDiscoverUiResult = { error("Not used by this test") }
    override val regionDiscoverMessage: suspend (RegionDiscoverUiResult) -> String? = { null }
    override val rebootDevice = null
    override val onImportPrivateKey = null
    override val onForgetKeepData: suspend () -> Unit = {}
    override val onForgetDeleteData: suspend () -> Unit = {}
    override val onFactoryReset: suspend () -> Boolean = { false }
    override val unfavoritedCount: suspend () -> Int = { 0 }
    override val onRemoveUnfavorited: suspend () -> StaleNodeRemovalResult = { StaleNodeRemovalResult(0, 0) }
}
