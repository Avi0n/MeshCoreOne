// AndroidOnly: WP-503 Real appearance selection, 48dp targets and 200-percent native text at compact width.
package com.meshcoreone.android.accessibility

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeId
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.designsystem.localizedName
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.feature.settings.app.appearance.AppearanceController
import com.meshcoreone.android.feature.settings.app.appearance.AppearanceSelection
import com.meshcoreone.android.feature.settings.app.appearance.ui.AppearanceScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAppearanceAccessibilityTest : NativeAccessibilityHarness() {
    @Test fun everyUnlockedThemeCanBeSelectedWithUnclippedSystemFontAndNativeTargets() {
        val controller = object : AppearanceController {
            override val selection = MutableStateFlow(AppearanceSelection(ThemeId.DEFAULT, AppColorSchemePreference.SYSTEM))
            override suspend fun setTheme(id: ThemeId) { selection.value = selection.value.copy(themeId = id) }
            override suspend fun setColorScheme(preference: AppColorSchemePreference) {
                selection.value = selection.value.copy(colorScheme = preference)
            }
        }
        content {
            MeshCoreTheme(motionScale = 0f) { AuditSurface { AppearanceScreen(controller) } }
        }
        val resources = host.get().resources
        var selected = ThemeId.DEFAULT
        for ((index, theme) in ThemeRegistry.allThemes.withIndex()) {
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(index + 1)
            val name = theme.localizedName(resources)
            val label = if (theme.id == selected) S.appearanceAccessibilityThemeCardSelectedLabel(resources, name)
                else S.appearanceAccessibilityThemeCardOwnedLabel(resources, name)
            val card = compose.onNodeWithContentDescription(label).assertIsDisplayed()
            if (theme.id != selected) card.performClick()
            compose.waitForIdle()
            assertEquals(theme.id, controller.selection.value.themeId)
            selected = theme.id
            compose.onNodeWithContentDescription(S.appearanceAccessibilityThemeCardSelectedLabel(resources, name)).assertIsDisplayed()
            val text = compose.onNode(
                hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                useUnmergedTree = true,
            )
            val layout = font200Layout(text)
            assertUnclippedContent(layout)
            assertFontPaintContainsInk(text)
            assertVisibleClickTargetsAtLeast48Dp()
        }
        assertEquals(10, ThemeRegistry.allThemes.size)
    }
}
