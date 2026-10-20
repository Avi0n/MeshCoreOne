// AndroidOnly: WP-407 Named language selection through the real Settings consumer at font200/RTL.
package com.meshcoreone.android.app.platform

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.R
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [31, 37], qualifiers = "w360dp-h900dp")
class LanguageSelectionRegistrationTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun eachLanguageHasOneNamedContainedSelectedActionAndDispatchesChinese() = withLanguageScreen("de") {
        val selectable = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        compose.onAllNodes(selectable).assertCountEquals(12)
        for (tag in AppLanguages.supported) {
            val locale = Locale.forLanguageTag(tag)
            val label = locale.getDisplayName(locale)
            val namedChoice = hasText(label) and hasClickAction() and selectable
            compose.onAllNodes(namedChoice).assertCountEquals(1)
            compose.onNode(namedChoice).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, tag == "de"))
            val row = compose.onNode(namedChoice).fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag label must stay inside its selectable row",
                text.left >= row.left && text.right <= row.right &&
                    text.top >= row.top && text.bottom <= row.bottom)
        }
        val chinese = Locale.forLanguageTag("zh-Hans").let { it.getDisplayName(it) }
        compose.onNode(hasText(chinese) and selectable).performScrollTo().performClick()
        assertEquals("zh-Hans", AppLanguages.selected(compose.activity))
    }

    @Test fun selectedChineseAndSystemResetUseTheExistingLocaleAdapter() = withLanguageScreen("zh-Hans") {
        val chinese = Locale.forLanguageTag("zh-Hans").let { it.getDisplayName(it) }
        compose.onNode(hasText(chinese) and isSelected()).performScrollTo().assertHasClickAction()
        compose.onNodeWithText(compose.activity.getString(R.string.platform_system_language))
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("", AppLanguages.selected(compose.activity))
    }

    private fun withLanguageScreen(initial: String, assertions: () -> Unit) = runTest {
        val activity = compose.activity
        val preferences = activity.getSharedPreferences("app_language", 0)
        val previous = AppLanguages.selected(activity)
        fun seed(tag: String) {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
            } else {
                check(preferences.edit().putString("language", tag).commit())
            }
        }
        val h = ContainerHarness(this, store)
        val storage = MeshCoreStorage.get(activity)
        try {
            seed(initial)
            val settings = AppSettingsFeature.create(h.container, storage)
            compose.runOnUiThread {
                activity.setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                        LocalLayoutDirection provides LayoutDirection.Rtl,
                    ) {
                        MeshCoreTheme(motionScale = 0f) {
                            NativeNavigationShell(h.container.navigation, settings = settings)
                        }
                    }
                }
            }
            compose.onNodeWithTag("tab:SETTINGS").performClick()
            compose.onNodeWithText(activity.getString(S.languageTitle)).performScrollTo().performClick()
            assertions()
            assertEquals(12, AppLanguages.supported.size)
            assertTrue(h.lifecycle.created.isEmpty())
        } finally {
            compose.runOnUiThread { activity.setContent {} }
            seed(previous)
            h.container.close()
            storage.close()
        }
    }
}
