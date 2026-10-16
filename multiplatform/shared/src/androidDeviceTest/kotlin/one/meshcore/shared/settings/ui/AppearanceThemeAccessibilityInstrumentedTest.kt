package one.meshcore.shared.settings.ui

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.billing.EntitlementStore
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.theme.AppearancePreferencesStore
import one.meshcore.shared.theme.ThemeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceThemeAccessibilityInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var composeView: View
    private lateinit var store: AppearancePreferencesStore
    private var density = 0f

    @Test
    fun themeChoicesExposeRadioCollectionAndAccessibilitySelection() {
        showAppearance()
        val choices = compose.onAllNodes(themeRow, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(ThemeRegistry.all.size, choices.size)
        choices.forEachIndexed { index, choice ->
            assertEquals(Role.RadioButton, choice.config.getOrElseNullable(SemanticsProperties.Role) { null })
            val group = requireNotNull(choice.parent)
            assertTrue(group.config.contains(SemanticsProperties.SelectableGroup))
            val visible = compose.onNode(SemanticsMatcher("Theme ${choice.id}") { it.id == choice.id }, true)
                .performScrollTo().fetchSemanticsNode()
            compose.runOnIdle {
                val provider = requireNotNull(composeView.accessibilityNodeProvider)
                val info = requireNotNull(provider.createAccessibilityNodeInfo(visible.id))
                val collection = requireNotNull(provider.createAccessibilityNodeInfo(group.id)?.collectionInfo)
                assertEquals(choices.size, collection.rowCount)
                assertEquals(1, collection.columnCount)
                val item = requireNotNull(info.collectionItemInfo)
                assertEquals(index, item.rowIndex)
                assertEquals(index == 0, item.isSelected)
                assertEquals(index != 0, info.isEnabled)
                assertEquals(index != 0, info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                if (index == 0) {
                    assertFalse(provider.performAction(visible.id, AccessibilityNodeInfo.ACTION_CLICK, null))
                }
            }
        }
        val target = choices[1]
        val visible = compose.onNode(SemanticsMatcher("Theme ${target.id}") { it.id == target.id }, true)
            .performScrollTo().fetchSemanticsNode()
        compose.runOnIdle {
            val provider = requireNotNull(composeView.accessibilityNodeProvider)
            assertTrue(provider.performAction(visible.id, AccessibilityNodeInfo.ACTION_CLICK, null))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(ThemeRegistry.all[1].id, store.selectedThemeId.value) }
        val updated = compose.onAllNodes(themeRow, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, updated.count { it.config[SemanticsProperties.Selected] })
        assertTrue(updated[1].config[SemanticsProperties.Selected])
    }

    @Test
    fun lockedThemeRejectsAccessibilitySelection() {
        showAppearance(EntitlementStore())
        val locked = compose.onAllNodes(themeRow, useUnmergedTree = true).fetchSemanticsNodes()[1]
        val visible = compose.onNode(SemanticsMatcher("Locked theme") { it.id == locked.id }, true)
            .performScrollTo().fetchSemanticsNode()
        compose.runOnIdle {
            val provider = requireNotNull(composeView.accessibilityNodeProvider)
            val info = requireNotNull(provider.createAccessibilityNodeInfo(visible.id))
            assertFalse(info.isEnabled)
            assertFalse(provider.performAction(visible.id, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(AppTheme.DEFAULT_ID, store.selectedThemeId.value)
        }
    }

    @Test
    fun themeRowsFillTheirCardAndRetainItsBackground() {
        showAppearance()
        val screen = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val choices = compose.onAllNodes(themeRow, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(ThemeRegistry.all.size, choices.size)
        choices.forEach { choice ->
            val row = compose.onNode(SemanticsMatcher("Theme ${choice.id}") { it.id == choice.id }, true)
                .performScrollTo()
            val visible = row.fetchSemanticsNode()
            assertEquals(screen.left + HUB_INSET * density, visible.boundsInRoot.left, HALF_PIXEL)
            assertEquals(screen.right - HUB_INSET * density, visible.boundsInRoot.right, HALF_PIXEL)
            val pixels = row.captureToImage().toPixelMap()
            assertTrue(pixels.width > EDGE_SAMPLE)
            assertEquals(Color.Magenta, pixels[pixels.width - EDGE_SAMPLE, pixels.height / 2])
        }
    }

    private fun showAppearance(entitlements: EntitlementStore? = null) {
        compose.setContent {
            composeView = LocalView.current
            density = LocalDensity.current.density
            val scope = rememberCoroutineScope()
            store = remember { AppearancePreferencesStore(InMemoryPreferencesStore(), scope) }
            MaterialTheme(colorScheme = lightColorScheme(surfaceContainerLow = Color.Magenta)) {
                AppearanceSettingsScreen(store, entitlements = entitlements)
            }
        }
    }

    private companion object {
        const val HUB_INSET = 16f
        const val HALF_PIXEL = 0.5f
        const val EDGE_SAMPLE = 2
        val themeRow = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
            .and(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
    }
}
