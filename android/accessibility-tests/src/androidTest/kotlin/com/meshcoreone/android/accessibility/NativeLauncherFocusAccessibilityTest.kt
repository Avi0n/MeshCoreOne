// AndroidOnly: WP-503 Observe real launcher/process graph tab selection and platform accessibility focus in an explicit completed-onboarding fixture.
package com.meshcoreone.android.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.app.navigation.SettingsDetail
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.onboarding.OnboardingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeLauncherFocusAccessibilityTest : DeviceAccessibilityHarness() {
    @Test fun realCachedChatDraftAndScrolledMessageSurviveCurrentWindowRecreationAtFont200() = withTouchExploration {
        val application = requireNotNull(activity.application as? MeshCoreApplication)
        val container = runBlocking { application.container.await() }
        val onboarding = requireNotNull(container.onboarding)
        val originalFlag = onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED)
        val sizes = shell("wm size")
        assertTrue(Regex("(?m)^Physical size: \\d+x\\d+$").containsMatchIn(sizes))
        val override = Regex("(?m)^Override size: (\\d+x\\d+)$").find(sizes)?.groupValues?.get(1)
        val radio = RadioId(UUID(0, 503001))
        val device = DeviceDTO(id = UUID(0, 503001), radioId = radio,
            publicKey = Bytes(ByteArray(32) { 0x50 }), nodeName = "Native resize fixture")
        val contact = ContactDTO(id = UUID(0, 503002), radioId = radio,
            publicKey = Bytes(ByteArray(32) { 0x51 }), name = "Cached \u4e2d\u6587 peer", lastHeardTimestamp = null)
        val conversation = ChatConversationID.dm(radio, contact.id)
        val drafts = container.chatsFeature.drafts
        val originalDraft = drafts.get(conversation)
        val draft = "Draft \u4e2d\u6587 \u0645\u0631\u062d\u0628\u0627"
        val history = List(36) { index ->
            val time = Instant.ofEpochSecond(1_700_000_000L + index)
            MessageDTO(id = UUID(0, 503100L + index), radioId = radio, contactID = contact.id,
                text = "Cached history $index \u4e2d\u6587", timestamp = time.epochSecond.toUInt(),
                createdAt = time, sortDate = time, direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED, isRead = true)
        }
        val target = history[15]
        var seeded = false
        try {
            runBlocking {
                assertEquals("Never overwrite pre-existing fixture data", null, container.processStore.fetchDevice(device.id))
                container.processStore.saveDevice(device)
                seeded = true
                container.processStore.saveContact(contact)
                history.forEach { container.processStore.saveMessage(it) }
            }
            compose.runOnUiThread {
                onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, true)
                container.navigation.navigateToChat(contact)
            }
            compose.waitUntil {
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).fetchSemanticsNodes().size == 1
            }
            val editor = descendants(root()).filter { it.isEditable }.toList().single()
            assertTrue("Refresh the actual native editor before its focus action", editor.refresh())
            assertNativeTargetContained(editor, root())
            println("WP503_NATIVE_CHAT_EDITOR|sdk=${android.os.Build.VERSION.SDK_INT}|focusedBefore=${editor.isFocused}" +
                "|focusActionAdvertised=${editor.actionList.any { it.id == AccessibilityNodeInfo.ACTION_FOCUS }}")
            if (!editor.isFocused) assertTrue("Acquire actual native input focus", editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            assertTrue(editor.refresh())
            assertTrue("Actual native editor must have input focus", editor.isFocused)
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
            }))
            compose.waitUntil { drafts.get(conversation) == draft }
            val edit = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
            assertFont200Unclipped(edit)
            val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex), useUnmergedTree = true)
            list.performScrollToNode(hasText(target.text))
            val text = compose.onNodeWithText(target.text, useUnmergedTree = true).assertIsDisplayed()
            assertFont200Unclipped(text)
            assertTrue(descendants(root()).any { it.contentDescription?.toString()?.contains(target.text) == true })
            val selected = container.navigation.state.value.chatsSelectedRoute
            val entry = container.navigation.state.value.activeStack.last()
            val stacks = container.navigation.state.value.stacks
            println("WP503_NATIVE_CHAT_WINDOW_BEFORE|sdk=${android.os.Build.VERSION.SDK_INT}|message=${target.id}" +
                "|textBounds=${text.fetchSemanticsNode().boundsInRoot}|entry=${entry.id}|draftSaved=true" +
                "|realStore=true|realGraph=true|physicalTalkBack=false")
            for ((size, tag) in listOf("900x900" to "navigation-rail", "360x900" to "navigation-hidden")) {
                resizeWindow(size, tag)
                assertEquals(stacks, container.navigation.state.value.stacks)
                assertEquals(entry, container.navigation.state.value.activeStack.last())
                assertEquals(selected, container.navigation.state.value.chatsSelectedRoute)
                assertEquals(draft, drafts.get(conversation))
                val currentEdit = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
                assertEquals(draft, currentEdit.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                assertFont200Unclipped(currentEdit)
                assertNativeTargetContained(descendants(root()).filter { it.isEditable }.toList().single(), root())
                val retained = compose.onNodeWithText(target.text, useUnmergedTree = true)
                println("WP503_NATIVE_CHAT_WINDOW_AFTER|sdk=${android.os.Build.VERSION.SDK_INT}|size=$size" +
                    "|message=${target.id}|visibleMatches=${compose.onAllNodesWithText(target.text, useUnmergedTree = true).fetchSemanticsNodes().size}" +
                    "|entry=${entry.id}|draftSaved=true|realStore=true|realGraph=true|physicalTalkBack=false")
                retained.assertIsDisplayed()
                assertFont200Unclipped(retained)
                assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
            }
        } finally {
            shell("wm size ${override ?: "reset"}")
            compose.runOnUiThread {
                if (container.navigation.state.value.activeStack.last().destination is NavigationDestination.Chat) {
                    container.navigation.back()
                }
                container.navigation.selectTab(AppTab.CHATS)
                drafts.set(conversation, originalDraft)
                onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, originalFlag)
            }
            if (seeded) runBlocking { container.processStore.deleteDeviceAndData(device.id) }
        }
    }

    @Test fun actualThemeProviderObservesDefaultAndReducedSystemMotionWithoutAnOverride() {
        val resolver = activity.contentResolver
        val original = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        val observation = AtomicReference<Pair<Float, Int>>()
        try {
            content {
                MeshCoreTheme {
                    val tokens = LocalMeshTheme.current
                    SideEffect { observation.set(tokens.motionScale to tokens.duration(300)) }
                    AuditSurface { Text("Native motion observer") }
                }
            }
            for (scale in listOf(0f, 1f, 0f)) {
                shell("settings put global animator_duration_scale $scale")
                assertEquals(scale, Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE), 0f)
                val expected = scale to if (scale == 0f) 0 else 300
                compose.waitUntil { observation.get() == expected }
                println("WP503_NATIVE_MOTION_PROVIDER|sdk=${android.os.Build.VERSION.SDK_INT}" +
                    "|system=$scale|observed=${observation.get().first}|duration=${observation.get().second}" +
                    "|explicitOverride=false|frameworkControl=true|physicalTalkBack=false")
            }
        } finally {
            shell("settings put global animator_duration_scale $original")
        }
    }

    @Test fun realLauncherDefaultAndReducedMotionKeepTabsAndNativeDetailBackReachable() = withTouchExploration {
        val application = requireNotNull(activity.application as? MeshCoreApplication)
        val container = runBlocking { application.container.await() }
        val onboarding = requireNotNull(container.onboarding)
        val originalFlag = onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED)
        val keys = listOf(Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
        val originalScales = keys.associateWith { Settings.Global.getFloat(activity.contentResolver, it) }
        try {
            compose.runOnUiThread { onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, true) }
            compose.waitUntil { compose.onAllNodesWithTag("tab:CHATS").fetchSemanticsNodes().size == 1 }
            assertEquals(AppTab.CHATS, container.navigation.state.value.selectedTab)
            val stacks = container.navigation.state.value.stacks
            for (scale in listOf(1f, 0f)) {
                for (key in keys) {
                    shell("settings put global $key $scale")
                    assertEquals(scale, Settings.Global.getFloat(activity.contentResolver, key), 0f)
                }
                for (tab in AppTab.entries.filter { it != AppTab.CHATS } + AppTab.CHATS) {
                    val before = nativeTab(root(), tab)
                    assertTrue(before.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                    assertTrue(before.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    compose.onNodeWithTag("tab:${tab.name}").assertIsSelected()
                    val window = root()
                    val after = nativeTab(window, tab)
                    assertTrue(after.isSelected && after.isAccessibilityFocused)
                    val bounds = assertNativeTargetContained(after, window)
                    assertEquals(tab, container.navigation.state.value.selectedTab)
                    assertEquals(stacks, container.navigation.state.value.stacks)
                    assertEquals(1, AppTab.entries.count { nativeTab(window, it).isSelected })
                    assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
                    assertFont200Unclipped(compose.onNodeWithTag("tab-label:${tab.name}", useUnmergedTree = true))
                    println("WP503_NATIVE_MOTION_TAB|sdk=${android.os.Build.VERSION.SDK_INT}|scale=$scale|tab=$tab" +
                        "|selected=true|focus=true|bounds=$bounds|realGraph=true|physicalTalkBack=false")
                }
                assertTrue(nativeTab(root(), AppTab.SETTINGS).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val language = activity.getString(AppSettingsStrings.languageTitle)
                compose.onNodeWithText(language).performScrollTo()
                val text = compose.onNodeWithText(language, useUnmergedTree = true)
                assertFont200Unclipped(text)
                val action = namedAction(root(), language)
                val languageBounds = assertNativeTargetContained(action, root())
                println("WP503_NATIVE_MOTION_LANGUAGE|sdk=${android.os.Build.VERSION.SDK_INT}|scale=$scale" +
                    "|bounds=$languageBounds|scrollTarget=mergedAction|fontScale=2.0|physicalTalkBack=false")
                assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.waitForIdle()
                assertEquals(SettingsDetail.LANGUAGE, container.navigation.state.value.selectedSetting)
                val window = root()
                val back = namedAction(window, activity.getString(R.string.scaffold_back))
                assertNativeTargetContained(back, window)
                assertTrue(back.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(back.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
                assertEquals(stacks, container.navigation.state.value.stacks)
                assertEquals(null, container.navigation.state.value.selectedSetting)
                assertTrue(nativeTab(root(), AppTab.CHATS).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                println("WP503_NATIVE_MOTION_BACK|sdk=${android.os.Build.VERSION.SDK_INT}|scale=$scale" +
                    "|languageNativeAction=true|nativeBack=true|stacksRestored=true|physicalTalkBack=false")
            }
        } finally {
            for ((key, scale) in originalScales) shell("settings put global $key $scale")
            compose.runOnUiThread {
                if (container.navigation.state.value.selectedSetting == SettingsDetail.LANGUAGE) container.navigation.back()
                container.navigation.selectTab(AppTab.CHATS)
                onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, originalFlag)
            }
        }
    }

    @Test fun realWindowResizeKeepsNamedRailActionsAndSettingsDetailBackStackReachable() = withTouchExploration {
        val application = requireNotNull(activity.application as? MeshCoreApplication)
        val container = runBlocking { application.container.await() }
        val onboarding = requireNotNull(container.onboarding)
        val originalFlag = onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED)
        val sizes = shell("wm size")
        assertTrue("The public window manager must report the real physical display",
            Regex("(?m)^Physical size: \\d+x\\d+$").containsMatchIn(sizes))
        val override = Regex("(?m)^Override size: (\\d+x\\d+)$").find(sizes)?.groupValues?.get(1)
        try {
            compose.runOnUiThread { onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, true) }
            compose.waitUntil { compose.onAllNodesWithTag("tab:CHATS").fetchSemanticsNodes().size == 1 }
            assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
            assertEquals(AppTab.CHATS, container.navigation.state.value.selectedTab)
            val stacks = container.navigation.state.value.stacks
            resizeWindow("900x900", "navigation-rail")
            compose.onNodeWithTag("list-detail").assertIsDisplayed()
            for (tab in AppTab.entries.filter { it != AppTab.CHATS } + AppTab.CHATS) {
                val before = nativeTab(root(), tab)
                assertTrue(before.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(before.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.onNodeWithTag("tab:${tab.name}").assertIsSelected()
                val window = root()
                val after = nativeTab(window, tab)
                assertTrue(after.isSelected && after.isAccessibilityFocused)
                assertNativeTargetContained(after, window)
                assertEquals(tab, container.navigation.state.value.selectedTab)
                assertEquals(stacks, container.navigation.state.value.stacks)
                assertEquals(1, AppTab.entries.count { nativeTab(window, it).isSelected })
                assertFont200Unclipped(compose.onNodeWithTag("navigation-heading", useUnmergedTree = true))
                println("WP503_NATIVE_RAIL|sdk=${android.os.Build.VERSION.SDK_INT}|tab=$tab" +
                    "|selected=true|focus=true|realGraph=true|fontScale=2.0|physicalTalkBack=false")
            }
            assertTrue(nativeTab(root(), AppTab.SETTINGS).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val language = activity.getString(AppSettingsStrings.languageTitle)
            val languageText = compose.onNodeWithText(language, useUnmergedTree = true).performScrollTo()
            assertFont200Unclipped(languageText)
            val action = namedAction(root(), language)
            assertNativeTargetContained(action, root())
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitForIdle()
            assertEquals(SettingsDetail.LANGUAGE, container.navigation.state.value.selectedSetting)
            val detailStacks = container.navigation.state.value.stacks
            val detail = container.navigation.state.value.activeStack.last()
            resizeWindow("360x900", "navigation-hidden")
            assertEquals(detailStacks, container.navigation.state.value.stacks)
            assertEquals(detail, container.navigation.state.value.activeStack.last())
            assertEquals(SettingsDetail.LANGUAGE, container.navigation.state.value.selectedSetting)
            assertEquals(AppTab.SETTINGS, container.navigation.state.value.selectedTab)
            assertFont200Unclipped(compose.onNodeWithTag("navigation-heading", useUnmergedTree = true))
            val window = root()
            val back = namedAction(window, activity.getString(R.string.scaffold_back))
            assertNativeTargetContained(back, window)
            val retainedFocus = back.isAccessibilityFocused
            assertTrue(back.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue(back.refresh())
            assertTrue(back.isAccessibilityFocused)
            assertTrue(back.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
            assertEquals(null, container.navigation.state.value.selectedSetting)
            assertEquals(stacks, container.navigation.state.value.stacks)
            assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
            println("WP503_NATIVE_WINDOW_DETAIL|sdk=${android.os.Build.VERSION.SDK_INT}|expanded=900x900" +
                "|compact=360x900|retainedEntry=${detail.id}|nativeBack=true|focusReacquired=true" +
                "|automaticBackFocusRetained=$retainedFocus|realGraph=true|physicalTalkBack=false")
        } finally {
            shell("wm size ${override ?: "reset"}")
            compose.runOnUiThread {
                if (container.navigation.state.value.selectedSetting == SettingsDetail.LANGUAGE) container.navigation.back()
                container.navigation.selectTab(AppTab.CHATS)
                onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, originalFlag)
            }
        }
    }

    @Test fun frameworkControlCanAcquireRealNativeAccessibilityFocus() = withTouchExploration {
        var calls = 0
        val label = "Native focus control"
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface { Button(onClick = { calls++ }) { Text(label) } }
            }
        }
        val native = namedAction(root(), label)
        assertTrue("Actual framework native focus must be available",
            native.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        assertTrue(native.refresh())
        assertTrue(native.isAccessibilityFocused)
        assertTrue(native.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.runOnIdle { assertEquals(1, calls) }
        assertTrue(namedAction(root(), label).isAccessibilityFocused)
        println("WP503_NATIVE_FOCUS_CONTROL|sdk=${android.os.Build.VERSION.SDK_INT}|focused=true|callback=1|physicalTalkBack=false")
    }

    @Test fun realDisconnectedLauncherKeepsFiveTabsSelectedAndPlatformFocusReachable() = withTouchExploration {
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
                println("WP503_NATIVE_LAUNCHER_SELECTION|sdk=${android.os.Build.VERSION.SDK_INT}|tab=$tab" +
                    "|nativeSelected=${after.isSelected}|accessibilityFocus=${after.isAccessibilityFocused}" +
                    "|realSelected=${container.navigation.state.value.selectedTab}")
                assertTrue("The native selected state must match the real navigation state", after.isSelected)
                assertTrue("Recomposition must retain native accessibility focus on the selected tab", after.isAccessibilityFocused)
                val tabs = AppTab.entries.map { nativeTab(window, it) }
                assertEquals("Exactly one actual native tab must be selected", 1, tabs.count { it.isSelected })
                val bounds = assertNativeTargetContained(after, window)
                val label = tabLabel(tab)
                assertTrue("The native selected tab must retain its spoken name",
                    descendants(after).any { it.text?.toString() == label || it.contentDescription?.toString() == label })
                assertFont200Unclipped(compose.onNodeWithTag("tab-label:${tab.name}", useUnmergedTree = true))
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

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation().executeShellCommand(command)).use {
            it.readBytes().toString(Charsets.UTF_8).trim().also { response ->
                println("WP503_NATIVE_WINDOW_COMMAND|command=$command|response=$response")
            }
        }

    private fun resizeWindow(size: String, tag: String) {
        val previous = activity
        shell("wm size $size")
        compose.waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        compose.runOnUiThread { activity.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL }
        compose.waitForIdle()
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        val bounds = Rect().also(root()::getBoundsInScreen)
        val width = bounds.width() / activity.resources.displayMetrics.density
        assertEquals("The actual window must reach the requested width", size.substringBefore('x').toFloat(), width, 0f)
        println("WP503_NATIVE_WINDOW|sdk=${android.os.Build.VERSION.SDK_INT}|size=$size|bounds=$bounds" +
            "|activityRecreated=${activity !== previous}|shell=$tag|fontScale=2.0|physicalTalkBack=false")
    }

    private fun assertNativeTargetContained(node: AccessibilityNodeInfo, window: AccessibilityNodeInfo): Rect {
        val bounds = Rect().also(node::getBoundsInScreen)
        val viewport = Rect().also(window::getBoundsInScreen)
        val density = activity.resources.displayMetrics.density
        assertTrue("The actual native target $bounds must be fully contained in $viewport", viewport.contains(bounds))
        assertTrue("The actual native target $bounds must be48dp at density=$density; text=${node.text}",
            bounds.width() / density >= 48f && bounds.height() / density >= 48f)
        return bounds
    }

    private fun withTouchExploration(block: () -> Unit) {
        val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val info = automation().serviceInfo
        println("WP503_NATIVE_FOCUS_SERVICE|sdk=${android.os.Build.VERSION.SDK_INT}|capabilities=${info.capabilities}" +
            "|flags=${info.flags}|touchExploration=${manager.isTouchExplorationEnabled}|physicalTalkBack=false")
        assertTrue("UiAutomation must actually support requesting touch exploration",
            info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION != 0)
        val original = info.flags
        try {
            info.flags = original or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            automation().serviceInfo = info
            compose.waitUntil { manager.isTouchExplorationEnabled }
            println("WP503_NATIVE_FOCUS_SERVICE_ACTIVE|sdk=${android.os.Build.VERSION.SDK_INT}" +
                "|touchExploration=${manager.isTouchExplorationEnabled}|physicalTalkBack=false")
            block()
        } finally {
            info.flags = original
            automation().serviceInfo = info
        }
    }

    private fun nativeTab(root: AccessibilityNodeInfo, tab: AppTab): AccessibilityNodeInfo =
        namedAction(root, tabLabel(tab), requireTab = true)

    private fun namedAction(root: AccessibilityNodeInfo, label: String, requireTab: Boolean = false): AccessibilityNodeInfo {
        val nodes = descendants(root).filter {
            it.text?.toString() == label || it.contentDescription?.toString() == label
        }.mapNotNull { named ->
            generateSequence(named) { it.parent }.firstOrNull { it.isClickable || it.isSelected }
        }.distinct().filter { action ->
            !requireTab || descendants(action).any {
                AccessibilityNodeInfoCompat.wrap(it).roleDescription?.toString() == "Tab"
            }
        }.toList()
        if (nodes.size != 1) {
            descendants(root).forEach {
                println("WP503_NATIVE_LAUNCHER_TREE|label=$label|class=${it.className}|text=${it.text}" +
                    "|description=${it.contentDescription}|clickable=${it.isClickable}|selected=${it.isSelected}" +
                    "|focusable=${it.isScreenReaderFocusable}|extras=${it.extras}|childCount=${it.childCount}")
            }
        }
        assertEquals("The real native tree must expose exactly one named $label action", 1, nodes.size)
        return nodes.single().also { assertTrue("Refresh the actual named $label action", it.refresh()) }
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
