// AndroidOnly: WP-503 Actual native shell semantics, system-font geometry and keyboard/Back focus; content is an explicit focus probe.
package com.meshcoreone.android.accessibility

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.app.navigation.SettingsDetail
import com.meshcoreone.android.app.navigation.titleResource
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeShellAccessibilityTest : NativeAccessibilityHarness() {
    private fun shell(navigation: NavigationCoordinator) {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    NativeNavigationShell(navigation, unreadCount = 123) { destination, _ ->
                        FocusProbe(destination)
                    }
                }
            }
        }
    }

    @Composable private fun FocusProbe(destination: NavigationDestination) {
        val id = when (destination) {
            is NavigationDestination.Root -> destination.tab.name
            is NavigationDestination.Setting -> destination.selection.sourceName
            else -> error("This shell audit does not mount other feature destinations")
        }
        var value by rememberSaveable { mutableStateOf("") }
        Column(Modifier.fillMaxSize().testTag("focus-probe:$id")) {
            Text("Shell-only focus probe", Modifier.testTag("probe-label:$id"))
            OutlinedTextField(value, { value = it }, Modifier.testTag("probe-editor:$id"),
                label = { Text("Focus probe") })
        }
    }

    @Test fun compactShellExposesFiveLabelledSelectedTabsAndUnclippedSystemText() {
        val navigation = NavigationCoordinator()
        shell(navigation)
        compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        for (tab in AppTab.entries) {
            val label = host.get().getString(tab.titleResource())
            val node = compose.onNodeWithTag("tab:${tab.name}").performClick().assertIsSelected()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .fetchSemanticsNode()
            assertTrue("The actual compact tab must expose its localized purpose", narrative(node).contains(label))
            compose.onNodeWithTag("navigation-heading").assertIsDisplayed()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            assertFont200Unclipped(compose.onNodeWithTag("tab-label:${tab.name}", useUnmergedTree = true))
            assertFont200Unclipped(compose.onNodeWithTag("navigation-heading", useUnmergedTree = true))
            assertVisibleClickTargetsAtLeast48Dp()
            assertEquals(tab, navigation.state.value.selectedTab)
        }
        val chats = compose.onNodeWithTag("tab:CHATS").fetchSemanticsNode()
        assertTrue(chats.config[SemanticsProperties.StateDescription].contains("123"))
        assertFont200Unclipped(compose.onNodeWithTag("navigation-unread-badge", useUnmergedTree = true))
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h900dp-mdpi")
    fun expandedRailRetainsNativeKeyboardFocusAndDraftAcrossTabsAndBack() {
        val navigation = NavigationCoordinator()
        shell(navigation)
        compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("list-detail").assertIsDisplayed()
        for (tab in AppTab.entries) {
            val node = compose.onNodeWithTag("tab:${tab.name}").performScrollTo().performClick().assertIsSelected()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .fetchSemanticsNode()
            assertTrue("The actual rail icon must expose its localized purpose",
                narrative(node).contains(host.get().getString(tab.titleResource())))
            assertVisibleClickTargetsAtLeast48Dp()
        }
        compose.runOnIdle { navigation.navigateToSetting(SettingsDetail.LANGUAGE) }
        compose.onNodeWithTag("probe-editor:language").performClick().performTextInput("\u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd")
        compose.onNodeWithTag("probe-editor:language").assertIsFocused()
        val entry = navigation.state.value.activeStack.last()
        compose.onNodeWithTag("tab:CHATS").performScrollTo().performClick()
        compose.onNodeWithTag("tab:SETTINGS").performScrollTo().performClick()
        compose.onNodeWithTag("probe-editor:language").assertTextContains("\u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd")
        assertEquals(entry, navigation.state.value.activeStack.last())
        compose.onNodeWithTag("probe-editor:language").performClick().assertIsFocused()
        compose.runOnUiThread { host.get().onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("focus-probe:SETTINGS").assertIsDisplayed()
        assertEquals(null, navigation.state.value.selectedSetting)
        compose.runOnUiThread { host.get().onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("focus-probe:CHATS").assertIsDisplayed()
        assertEquals(AppTab.CHATS, navigation.state.value.selectedTab)
    }
}
