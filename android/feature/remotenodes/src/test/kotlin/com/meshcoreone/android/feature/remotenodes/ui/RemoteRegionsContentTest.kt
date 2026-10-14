// AndroidOnly: WP-313 Mounted Robolectric regression of scaled region gutters and region-specific flood switch semantics; not TalkBack or device evidence.
package com.meshcoreone.android.feature.remotenodes.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.feature.remotenodes.settings.CommandRecorder
import com.meshcoreone.android.feature.remotenodes.settings.RepeaterRegionEntry
import com.meshcoreone.android.feature.remotenodes.settings.RepeaterRegionsStateHolder
import com.meshcoreone.android.feature.remotenodes.settings.region
import com.meshcoreone.android.feature.remotenodes.settings.regionHolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "w360dp-h800dp")
class RemoteRegionsContentTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>

    @Before fun openWindow() { controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible() }
    @After fun closeWindow() { controller.pause().stop().destroy() }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val recorder = CommandRecorder()
    private val confirmed = mutableListOf<Int>()
    private var confirmedAction: (suspend () -> Unit)? = null

    private val regions = listOf(
        region("*", null, 0),
        region("on", "*", 1),
        region("gta", "on", 2),
        region("downtown", "gta", 3),
        region("east", "downtown", 4),
        region("north", "east", 5),
    )

    private fun mount(
        fontScale: Float,
        enabled: Boolean = true,
        holder: RepeaterRegionsStateHolder = regionHolder(recorder).regions,
    ) {
        holder.setRegions(regions)
        holder.setRegionsExpanded(true)
        controller.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteRegionsContent(holder, enabled) { caption, action ->
                        confirmed += caption
                        confirmedAction = action
                    }
                }
            }
        }
    }

    private fun switchWithLabel(label: String) =
        compose.onNode(hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    private fun labelFor(region: RepeaterRegionEntry): String = when {
        region.isUnscoped -> context.getString(L.remoteNodesSettingsRegionsAllTraffic)
        region.parentName == null -> region.name
        else -> context.getString(
            R.string.l10n_app_remotenodes_remotenodes_settings_regions_childof,
            region.name,
            region.namedParent ?: context.getString(L.remoteNodesSettingsRegionsAllTraffic),
        )
    }

    private fun nameLeft(region: RepeaterRegionEntry) = compose
        .onNodeWithText(
            if (region.isUnscoped) context.getString(L.remoteNodesSettingsRegionsAllTrafficWildcard) else region.name,
            useUnmergedTree = true,
        )
        .getUnclippedBoundsInRoot().left

    private fun assertGutters(fontScale: Float, expectedIndentDp: Float, expectedVisibleDepths: List<Int>) {
        mount(fontScale)
        val rootLeft = nameLeft(regions.first())
        regions.zip(expectedVisibleDepths).forEach { (region, depth) ->
            assertEquals(expectedIndentDp * depth, (nameLeft(region) - rootLeft).value, 0.5f, "${region.name} at ${fontScale}x")
        }
    }

    private fun assertSwitchSemantics() {
        val hint = context.getString(L.remoteNodesSettingsRegionsFloodToggleHint)
        val labels = regions.map(::labelFor)
        assertEquals(labels.size, labels.toSet().size, "Switch identities must be unique across regions")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(regions.size)
        labels.forEach { label ->
            val node = switchWithLabel(label)
            node.assertIsOn().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            assertEquals(hint, node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label, "Hint for $label")
        }
    }

    @Test
    fun `default font scale mounts source gutters and unique region switch semantics`() {
        assertGutters(1f, 12f, listOf(0, 1, 2, 3, 4, 4))
        assertSwitchSemantics()
    }

    @Test
    fun `200 percent font scale caps gutters at the 48dp width and keeps switch semantics`() {
        assertGutters(2f, 24f, listOf(0, 1, 2, 2, 2, 2))
        assertSwitchSemantics()
    }

    @Test
    fun `child of Unscoped and named parent labels are distinct`() {
        mount(1f)
        val allTraffic = context.getString(L.remoteNodesSettingsRegionsAllTraffic)
        assertTrue(labelFor(regions[1]).contains(allTraffic))
        assertTrue(labelFor(regions[2]).contains("on") && !labelFor(regions[2]).contains(allTraffic))
        switchWithLabel(labelFor(regions[1])).assertExists()
        switchWithLabel(labelFor(regions[2])).assertExists()
    }

    @Test
    fun `switch confirms and runs the same region action only after confirmation`() {
        recorder.repliesByCommand["region"] = regions.joinToString(separator = "\n", postfix = "\n") { region ->
            "${" ".repeat(region.depth)}${region.name} F"
        }
        val holder = regionHolder(recorder).regions
        runBlocking { holder.fetchRegions() }
        recorder.resetCommands()
        mount(1f, holder = holder)
        switchWithLabel(labelFor(regions[2])).performClick()
        assertEquals(listOf(L.remoteNodesSettingsRegionsFloodToggleCaption), confirmed)
        assertTrue(recorder.commands.isEmpty(), "The action must wait for explicit confirmation")
        runBlocking { checkNotNull(confirmedAction).invoke() }
        assertEquals(listOf("region denyf gta"), recorder.commands)
        assertFalse(holder.state.value.regions.single { it.name == "gta" }.floodAllowed)
        assertTrue(holder.state.value.regions.filter { it.name != "gta" }.all { it.floodAllowed })
    }

    @Test
    fun `disabled content disables every switch`() {
        mount(1f, enabled = false)
        regions.forEach { switchWithLabel(labelFor(it)).assertIsNotEnabled() }
    }
}
