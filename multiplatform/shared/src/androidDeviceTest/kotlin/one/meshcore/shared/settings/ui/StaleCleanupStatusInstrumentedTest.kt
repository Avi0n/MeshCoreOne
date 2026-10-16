package one.meshcore.shared.settings.ui

import android.view.View
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_nodes_stale_cleanup_last_run
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.namedRelativeLabel
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaleCleanupStatusInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var composeView: View

    @Test
    fun lastRunUsesSharedCardRowAndRemainsOutsideRadioChoices() {
        val lastRun = mutableStateOf(0L)
        val threshold = mutableStateOf(STALE_DAYS)
        lateinit var statusLabel: String
        var density = 0f
        compose.setContent {
            composeView = LocalView.current
            density = LocalDensity.current.density
            statusLabel = formatTemplate(
                stringResource(Res.string.settings_nodes_stale_cleanup_last_run),
                listOf(namedRelativeLabel(LAST_RUN, NOW)),
            )
            MaterialTheme(colorScheme = lightColorScheme(surfaceContainerLow = Color.Magenta)) {
                StaleNodeCleanupSection(
                    thresholdDays = threshold.value,
                    isDeviceConnected = true,
                    onThresholdChange = { threshold.value = it },
                    lastRunSeconds = lastRun.value,
                    nowSeconds = { NOW },
                    modifier = Modifier.padding(horizontal = HUB_INSET.dp),
                )
            }
        }
        val group = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
            useUnmergedTree = true,
        ).assertCountEquals(1).onFirst()
        val before = group.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(statusLabel).assertDoesNotExist()

        compose.runOnIdle { lastRun.value = LAST_RUN }
        val status = compose.onNodeWithText(statusLabel).assertExists().fetchSemanticsNode()
        val collection = group.fetchSemanticsNode()
        val after = collection.boundsInRoot
        val addedHeight = after.height - before.height
        assertTrue(addedHeight >= MINIMUM_ROW_HEIGHT * density - HALF_PIXEL)
        assertEquals(before.width, after.width, HALF_PIXEL)
        assertEquals(after.left, status.boundsInRoot.left, HALF_PIXEL)
        assertEquals(after.right, status.boundsInRoot.right, HALF_PIXEL)
        val text = compose.onNodeWithText(statusLabel, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(after.left + HUB_INSET * density, text.boundsInRoot.left, HALF_PIXEL)
        val pixels = group.captureToImage().toPixelMap()
        val sampleY = (pixels.height - addedHeight / 2).toInt()
        assertTrue(pixels.width > EDGE_SAMPLE && sampleY in 0 until pixels.height)
        assertEquals(Color.Magenta, pixels[pixels.width - EDGE_SAMPLE, sampleY])
        assertRadioCollection(collection.id, status.id)

        compose.runOnIdle { threshold.value = 0 }
        compose.onNodeWithText(statusLabel).assertDoesNotExist()
        compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    private fun assertRadioCollection(groupId: Int, statusId: Int) {
        compose.runOnIdle {
            val provider = requireNotNull(composeView.accessibilityNodeProvider)
            val info = requireNotNull(provider.createAccessibilityNodeInfo(groupId)?.collectionInfo)
            assertEquals(RADIO_CHOICES, info.rowCount)
            assertEquals(1, info.columnCount)
            val statusInfo = requireNotNull(provider.createAccessibilityNodeInfo(statusId))
            assertNull(statusInfo.collectionItemInfo)
        }
    }

    private companion object {
        const val STALE_DAYS = 7
        const val LAST_RUN = 1200L
        const val NOW = 1800L
        const val HUB_INSET = 16
        const val MINIMUM_ROW_HEIGHT = 56
        const val HALF_PIXEL = 0.5f
        const val EDGE_SAMPLE = 2
        const val RADIO_CHOICES = 5
    }
}
