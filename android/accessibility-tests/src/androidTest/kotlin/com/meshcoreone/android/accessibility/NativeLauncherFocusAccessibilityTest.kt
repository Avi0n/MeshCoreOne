// AndroidOnly: WP-503 Observe real launcher/process graph tab selection and platform accessibility focus in an explicit completed-onboarding fixture.
package com.meshcoreone.android.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.feature.onboarding.OnboardingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLauncherFocusAccessibilityTest : DeviceAccessibilityHarness() {
    @Test fun realDisconnectedLauncherKeepsFiveTabsSelectedAndPlatformFocusReachable() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
        val application = requireNotNull(activity.application as? MeshCoreApplication)
        val container = runBlocking { application.container.await() }
        assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
        val onboarding = requireNotNull(container.onboarding) { "The real launcher must have actual onboarding bindings" }
        val original = onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED)
        try {
            compose.runOnUiThread { onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, true) }
            compose.waitUntil { compose.onAllNodesWithTag("tab:CHATS").fetchSemanticsNodes().size == 1 }
            assertEquals(AppTab.CHATS, container.navigation.state.value.selectedTab)
            for (tab in AppTab.entries.filter { it != AppTab.CHATS } + AppTab.CHATS) {
                val tag = "tab:${tab.name}"
                val before = nativeTab(root(), tab)
                assertTrue("The real native tab must be actionable", before.isClickable)
                assertTrue("Platform accessibility focus must reach $tag",
                    before.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue("Refresh the actual focused native node", before.refresh())
                assertTrue("Native accessibility focus was not established for $tag", before.isAccessibilityFocused)
                assertTrue("The native tab action must execute", before.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.onNodeWithTag(tag).assertIsSelected()
                assertEquals(tab, container.navigation.state.value.selectedTab)
                assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
                val window = root()
                val after = nativeTab(window, tab)
                assertTrue("The native selected state must match the real navigation state", after.isSelected)
                assertTrue("Recomposition must retain native accessibility focus on the selected tab", after.isAccessibilityFocused)
                val tabs = AppTab.entries.map { nativeTab(window, it) }
                assertEquals("Exactly one actual native tab must be selected", 1, tabs.count { it.isSelected })
                val bounds = Rect().also(after::getBoundsInScreen)
                val viewport = Rect().also(window::getBoundsInScreen)
                val density = activity.resources.displayMetrics.density
                assertTrue("The native tab target must be fully contained", viewport.contains(bounds))
                assertTrue("The real native tab target must be48dp", bounds.width() / density >= 48f && bounds.height() / density >= 48f)
                val label = tabLabel(tab)
                assertTrue("The native selected tab must retain its spoken name",
                    descendants(after).any { it.text?.toString() == label || it.contentDescription?.toString() == label })
                assertFont200Unclipped(compose.onNodeWithTag("tab-label:${tab.name}"))
                println("WP503_NATIVE_LAUNCHER_FOCUS|sdk=${android.os.Build.VERSION.SDK_INT}|tab=$tab|nativeSelected=true" +
                    "|accessibilityFocus=true|bounds=$bounds|fontScale=2.0|completedPreferenceSeeded=true" +
                    "|realGraph=true|realRadio=false|physicalTalkBack=false")
            }
        } finally {
            compose.runOnUiThread { onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, original) }
        }
    }

    private fun root(): AccessibilityNodeInfo {
        compose.waitForIdle()
        automation().waitForIdle(100, 1000)
        var candidate: AccessibilityNodeInfo? = null
        compose.waitUntil {
            candidate = automation().rootInActiveWindow
            candidate?.packageName?.toString() == activity.packageName
        }
        return requireNotNull(candidate).also { assertTrue("Refresh the real launcher window", it.refresh()) }
    }

    private fun automation() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun nativeTab(root: AccessibilityNodeInfo, tab: AppTab): AccessibilityNodeInfo {
        val label = tabLabel(tab)
        val nodes = descendants(root).filter {
            it.text?.toString() == label || it.contentDescription?.toString() == label
        }.mapNotNull { named ->
            generateSequence(named) { it.parent }.firstOrNull { it.isClickable || it.isSelected }
        }.distinct().toList()
        if (nodes.size != 1) {
            descendants(root).forEach {
                println("WP503_NATIVE_LAUNCHER_TREE|tab=$tab|class=${it.className}|text=${it.text}" +
                    "|description=${it.contentDescription}|clickable=${it.isClickable}|selected=${it.isSelected}" +
                    "|focusable=${it.isScreenReaderFocusable}|extras=${it.extras}|childCount=${it.childCount}")
            }
        }
        assertEquals("The real native tree must expose exactly one named $tab action", 1, nodes.size)
        return nodes.single()
    }

    private fun tabLabel(tab: AppTab): String = activity.getString(when (tab) {
        AppTab.CHATS -> R.string.tab_chats
        AppTab.NODES -> R.string.tab_nodes
        AppTab.MAP -> R.string.tab_map
        AppTab.TOOLS -> R.string.tab_tools
        AppTab.SETTINGS -> R.string.tab_settings
    })

    private fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) {
            yieldAll(descendants(requireNotNull(node.getChild(index)) { "A declared native child became unavailable" }))
        }
    }
}
