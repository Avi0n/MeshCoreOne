// AndroidOnly: WP-503 Observe real launcher/process graph, cached composer, system motion, queued effects and native focus/window/IME behavior in explicit isolated fixtures.
package com.meshcoreone.android.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.designsystem.ThemeServiceState
import com.meshcoreone.android.core.designsystem.labelResource
import com.meshcoreone.android.core.designsystem.localizedName
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeLauncherFocusAccessibilityTest : DeviceAccessibilityHarness() {
    // Geometry failures still fail JUnit, without skipping later theme/persistence observations.
    @get:Rule val appearanceTargetErrors = ErrorCollector()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun actualSubcompositionQueuesEffectsAndDetectsInlineSchedulingCounterexample() {
        val measuring = AtomicBoolean(false)
        val phase = AtomicReference("frame")
        val queuedDuringMeasure = ConcurrentHashMap<String, Boolean>()
        val inlineDuringMeasure = ConcurrentHashMap<String, Boolean>()
        val inlineScope = CoroutineScope(UnconfinedTestDispatcher())
        val label = "Native effect scheduling"
        try {
            content {
                MeshCoreTheme {
                    AuditSurface {
                        SubcomposeLayout { constraints ->
                            check(measuring.compareAndSet(false, true))
                            val measuredPhase = phase.get()
                            val placeables = try {
                                subcompose(measuredPhase) {
                                    LaunchedEffect(Unit) {
                                        queuedDuringMeasure.putIfAbsent(measuredPhase, measuring.get())
                                    }
                                    SideEffect {
                                        inlineScope.launch {
                                            inlineDuringMeasure.putIfAbsent(measuredPhase, measuring.get())
                                        }
                                    }
                                    Text(label)
                                }.map { it.measure(constraints) }
                            } finally {
                                measuring.set(false)
                            }
                            val placeable = placeables.single()
                            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                        }
                    }
                }
            }
            compose.waitUntil { queuedDuringMeasure.containsKey("frame") && inlineDuringMeasure.containsKey("frame") }
            // Probe platform measurement outside a test-clock frame.
            compose.runOnUiThread {
                phase.set("native")
                val view = requireNotNull(activity.findViewById<View>(android.R.id.content))
                assertTrue(view.width > 8 && view.height > 0)
                view.forceLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(view.width - 8, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY))
            }
            compose.waitUntil { queuedDuringMeasure.containsKey("native") && inlineDuringMeasure.containsKey("native") }
            for (measuredPhase in listOf("frame", "native")) {
                println("WP503_NATIVE_EFFECT_SCHEDULING|sdk=${android.os.Build.VERSION.SDK_INT}|phase=$measuredPhase" +
                    "|inlineDuringMeasure=${inlineDuringMeasure[measuredPhase]}|auditedDuringMeasure=${queuedDuringMeasure[measuredPhase]}")
                assertEquals("The inline control must detect $measuredPhase subcomposition measurement", true, inlineDuringMeasure[measuredPhase])
                assertEquals("Audited effects must not resume inside $measuredPhase measurement", false, queuedDuringMeasure[measuredPhase])
            }
            assertFont200Unclipped(compose.onNodeWithText(label))
        } finally {
            inlineScope.cancel()
        }
    }

    @Test fun realCachedChatImeKeepsNativeComposerVisibleAndBackPreservesDraft() = withTouchExploration {
        val application = requireNotNull(activity.application as? MeshCoreApplication)
        val container = runBlocking { application.container.await() }
        val onboarding = requireNotNull(container.onboarding)
        val originalFlag = onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED)
        val radio = RadioId(UUID(0, 503201))
        val device = DeviceDTO(id = UUID(0, 503201), radioId = radio,
            publicKey = Bytes(ByteArray(32) { 0x52 }), nodeName = "Native keyboard fixture")
        val contact = ContactDTO(id = UUID(0, 503202), radioId = radio,
            publicKey = Bytes(ByteArray(32) { 0x53 }), name = "Keyboard \u4e2d\u6587 peer", lastHeardTimestamp = null)
        val conversation = ChatConversationID.dm(radio, contact.id)
        val drafts = container.chatsFeature.drafts
        val originalDraft = drafts.get(conversation)
        val draft = "Draft \u4e2d\u6587 \u0645\u0631\u062d\u0628\u0627"
        val originalWindowFlags = automation().serviceInfo.flags
        var seeded = false
        fun appWindow(): AccessibilityNodeInfo {
            var window: AccessibilityNodeInfo? = null
            compose.waitUntil {
                window = automation().windows.mapNotNull { it.root }
                    .singleOrNull { it.packageName?.toString() == activity.packageName }
                window != null
            }
            return requireNotNull(window).also { assertTrue(it.refresh()) }
        }
        fun imeVisible(): Boolean {
            var visible = false
            compose.runOnUiThread {
                visible = requireNotNull(activity.window.decorView.rootWindowInsets).isVisible(WindowInsets.Type.ime())
            }
            return visible
        }
        try {
            val service = automation().serviceInfo
            service.flags = originalWindowFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation().serviceInfo = service
            runBlocking {
                assertEquals("Never overwrite pre-existing fixture data", null, container.processStore.fetchDevice(device.id))
                container.processStore.saveDevice(device)
                seeded = true
                container.processStore.saveContact(contact)
            }
            compose.runOnUiThread {
                onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, true)
                container.navigation.navigateToChat(contact)
            }
            compose.waitUntil {
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).fetchSemanticsNodes().size == 1
            }
            val entry = container.navigation.state.value.activeStack.last()
            val stacks = container.navigation.state.value.stacks
            val editor = descendants(appWindow()).filter { it.isEditable }.toList().single()
            assertTrue("Observe the actual hidden-to-visible keyboard transition", !imeVisible())
            assertTrue("Actual native editor click must open the platform input session",
                editor.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitForIdle()
            compose.waitUntil { editor.refresh() && editor.isFocused }
            assertTrue("Actual keyboard editor must have native input focus", editor.isFocused)
            val input = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            compose.waitUntil {
                var accepting = false
                compose.runOnUiThread { accepting = input.isAcceptingText }
                accepting
            }
            println("WP503_NATIVE_CHAT_IME_INPUT|sdk=${android.os.Build.VERSION.SDK_INT}" +
                "|nativeInputFocus=true|acceptingText=true|physicalTalkBack=false")
            compose.waitUntil { imeVisible() }
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
            }))
            compose.waitUntil { drafts.get(conversation) == draft }
            assertFont200Unclipped(compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)))
            val window = appWindow()
            val visible = Rect()
            compose.runOnUiThread {
                val decor = activity.window.decorView
                val bottom = requireNotNull(decor.rootWindowInsets).getInsets(WindowInsets.Type.ime()).bottom
                assertTrue("Actual visible IME must allocate a nonzero inset", bottom > 0)
                val position = IntArray(2).also(decor::getLocationOnScreen)
                visible.set(position[0], position[1], position[0] + decor.width, position[1] + decor.height - bottom)
                println("WP503_NATIVE_CHAT_IME_INSETS|sdk=${android.os.Build.VERSION.SDK_INT}|imeBottom=$bottom" +
                    "|decorHeight=${decor.height}|visible=$visible|actualIme=true|physicalTalkBack=false")
            }
            val nativeEditor = descendants(window).filter { it.isEditable }.toList().single()
            val sendLabel = activity.getString(AppChatsStrings.chatsInputSendMessage)
            val send = descendants(window).filter { it.contentDescription?.toString() == sendLabel }.toList().single()
            val share = namedAction(window, activity.getString(AppChatsStrings.chatsInputShareButtonAccessibilityLabel))
            for (node in listOf(nativeEditor, send, share)) {
                val bounds = assertNativeTargetContained(node, window)
                assertTrue("The actual native composer target $bounds must be above the visible IME in $visible",
                    visible.contains(bounds))
                println("WP503_NATIVE_CHAT_IME_TARGET|sdk=${android.os.Build.VERSION.SDK_INT}|name=${node.contentDescription}" +
                    "|bounds=$bounds|visible=$visible|native48dp=true|actualIme=true|physicalTalkBack=false")
            }
            assertTrue("Disconnected send must remain disabled with the actual keyboard open", !send.isEnabled)
            assertEquals(activity.getString(AppChatsStrings.chatsInputRequiresConnection),
                AccessibilityNodeInfoCompat.wrap(send).stateDescription?.toString())
            assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
            assertEquals(draft, nativeEditor.text?.toString())
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil { !imeVisible() }
            assertEquals(entry, container.navigation.state.value.activeStack.last())
            assertEquals(stacks, container.navigation.state.value.stacks)
            assertEquals(draft, drafts.get(conversation))
            assertEquals(draft, compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertFont200Unclipped(compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)))
            assertNativeTargetContained(descendants(appWindow()).filter { it.isEditable }.toList().single(), appWindow())
            println("WP503_NATIVE_CHAT_IME_BACK|sdk=${android.os.Build.VERSION.SDK_INT}|imeVisible=false" +
                "|entry=${entry.id}|stacksRetained=true|draftSaved=true|disconnectedSendDisabled=true" +
                "|realStore=true|realGraph=true|physicalTalkBack=false")
            val shareLabel = activity.getString(AppChatsStrings.chatsInputShareButtonAccessibilityLabel)
            val restoredShare = namedAction(appWindow(), shareLabel)
            assertTrue(restoredShare.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue(restoredShare.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val contactLabel = activity.getString(AppChatsStrings.chatsShareContact)
            compose.waitUntil { compose.onAllNodesWithText(contactLabel).fetchSemanticsNodes().size == 1 }
            for (label in listOf(activity.getString(AppChatsStrings.chatsShareLocation), contactLabel,
                activity.getString(AppChatsStrings.chatsShareMyInfo))) {
                compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                assertFont200Unclipped(compose.onNodeWithText(label, useUnmergedTree = true))
            }
            compose.onNodeWithText(activity.getString(AppChatsStrings.chatsShareLocation)).assertIsNotEnabled()
            compose.onNodeWithText(activity.getString(AppChatsStrings.chatsShareMyInfo)).assertIsNotEnabled()
            val popup = root()
            val contactAction = namedAction(popup, contactLabel)
            val contactBounds = assertNativeTargetContained(contactAction, popup)
            assertTrue(contactAction.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue(contactAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val inDialog = SemanticsMatcher("Inside the actual contact picker dialog") { node ->
                generateSequence(node.parent) { it.parent }.any { it.config.contains(SemanticsProperties.IsDialog) }
            }
            val cachedContact = compose.onNode(hasText(contact.displayName).and(inDialog), useUnmergedTree = true)
            cachedContact.assertIsDisplayed()
            assertFont200Unclipped(cachedContact)
            val dialog = root()
            val cachedAction = namedAction(dialog, contact.displayName)
            val cachedBounds = assertNativeTargetContained(cachedAction, dialog)
            val cancelLabel = activity.getString(AppLocalizableStrings.commonCancel)
            assertFont200Unclipped(compose.onNode(hasText(cancelLabel).and(inDialog), useUnmergedTree = true))
            val cancel = namedAction(dialog, cancelLabel)
            val cancelBounds = assertNativeTargetContained(cancel, dialog)
            assertTrue(cancel.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil {
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(entry, container.navigation.state.value.activeStack.last())
            assertEquals(stacks, container.navigation.state.value.stacks)
            assertEquals(draft, drafts.get(conversation))
            assertTrue("Cancelling the real contact picker must not reopen the keyboard", !imeVisible())
            val returnedShare = namedAction(appWindow(), shareLabel)
            val automaticFocus = returnedShare.isAccessibilityFocused
            println("WP503_NATIVE_CHAT_SHARE_RETURN_FOCUS|sdk=${android.os.Build.VERSION.SDK_INT}" +
                "|automaticNativeFocus=$automaticFocus|physicalTalkBack=false")
            if (!automaticFocus) assertTrue(returnedShare.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue(returnedShare.refresh())
            assertTrue(returnedShare.isAccessibilityFocused)
            println("WP503_NATIVE_CHAT_SHARE_CANCEL|sdk=${android.os.Build.VERSION.SDK_INT}" +
                "|contactMenu=$contactBounds|cachedContact=$cachedBounds|cancel=$cancelBounds" +
                "|optionalSharesDisabled=true|automaticNativeFocus=$automaticFocus|nativeFocusReacquired=true" +
                "|entry=${entry.id}|stacksRetained=true|draftSaved=true|realStore=true|realGraph=true|physicalTalkBack=false")
        } finally {
            compose.runOnUiThread {
                requireNotNull(activity.window.decorView.windowInsetsController).hide(WindowInsets.Type.ime())
                if (container.navigation.state.value.activeStack.last().destination is NavigationDestination.Chat) {
                    container.navigation.back()
                }
                container.navigation.selectTab(AppTab.CHATS)
                drafts.set(conversation, originalDraft)
                onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, originalFlag)
            }
            val service = automation().serviceInfo
            service.flags = originalWindowFlags
            automation().serviceInfo = service
            if (seeded) runBlocking { container.processStore.deleteDeviceAndData(device.id) }
        }
    }

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
            val historyBodies = history.map { it.text }.toSet()
            fun captureViewport(): Pair<Float, List<String>> {
                val viewport = list.fetchSemanticsNode()
                val row = compose.onNodeWithContentDescription(target.text, useUnmergedTree = true).fetchSemanticsNode()
                val messages = compose.onAllNodes(SemanticsMatcher("An actual cached message row") {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()
                        ?.let { body -> body in historyBodies } == true
                }, useUnmergedTree = true).fetchSemanticsNodes()
                return compose.runOnIdle {
                    val coordinates = row.layoutInfo.coordinates
                    assertTrue("The actual row layout must remain placed and attached",
                        row.layoutInfo.isPlaced && coordinates.isAttached)
                    val viewportBounds = viewport.boundsInRoot
                    val rowBottom = coordinates.positionInRoot().y + coordinates.size.height
                    val offset = rowBottom - viewportBounds.bottom
                    val visibleMessages = messages.filter { node ->
                        generateSequence(node.parent) { it.parent }.any { it.id == viewport.id } &&
                            node.layoutInfo.isPlaced && node.layoutInfo.coordinates.isAttached
                    }.map { node ->
                        val actual = node.layoutInfo.coordinates
                        val position = actual.positionInRoot()
                        val bounds = androidx.compose.ui.geometry.Rect(
                            position.x, position.y,
                            position.x + actual.size.width, position.y + actual.size.height,
                        )
                        assertTrue("Observed row geometry must be finite",
                            listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() })
                        node.config[SemanticsProperties.ContentDescription].single() to bounds
                    }.filter { (_, bounds) ->
                        bounds.width > 0f && bounds.height > 0f && bounds.overlaps(viewportBounds)
                    }.sortedBy { (_, bounds) -> bounds.top }
                    for ((body, bounds) in visibleMessages) {
                        println("WP503_NATIVE_CHAT_ROW_GEOMETRY|body=$body|bounds=$bounds" +
                            "|viewport=$viewportBounds|coordinateSource=actualLayout|mainThreadSnapshot=true")
                    }
                    val identities = visibleMessages.map { it.first }
                    assertTrue("Actual visible rows must be observed, not inferred from the target identity", identities.isNotEmpty())
                    assertEquals("Observe each actual visible message once", identities.size, identities.distinct().size)
                    println("WP503_NATIVE_CHAT_VIEWPORT|sdk=${android.os.Build.VERSION.SDK_INT}|message=${target.id}" +
                        "|rowBottomPx=$rowBottom|viewportBottomPx=${viewportBounds.bottom}" +
                        "|withinRowOffsetPx=$offset|rowHeightPx=${coordinates.size.height}|visibleRows=$identities")
                    offset to identities
                }
            }
            val axis = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("This actual production timeline must expose its reverse-layout scroll direction", axis.reverseScrolling)
            val alignDelta = 12f - captureViewport().first
            list.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, alignDelta)) }
            compose.waitForIdle()
            val firstOffset = captureViewport()
            assertEquals("Observe the first actual within-row position in pixels", 12f, firstOffset.first, 0.5f)
            assertEquals("The measured row must be the actual bottommost visible message", target.text, firstOffset.second.last())
            list.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 12f)) }
            compose.waitForIdle()
            val offsetOnly = captureViewport()
            assertEquals("An offset-only scroll must retain the complete observed visible message set",
                firstOffset.second, offsetOnly.second)
            assertEquals("The actual row must move a further12px without relying on message identity",
                24f, offsetOnly.first, 0.5f)
            assertTrue("The captured viewport boundary must lie inside this actual row",
                offsetOnly.first > 0f && offsetOnly.first <
                    compose.onNodeWithContentDescription(target.text, useUnmergedTree = true)
                        .fetchSemanticsNode().layoutInfo.coordinates.size.height)
            val selected = container.navigation.state.value.chatsSelectedRoute
            val entry = container.navigation.state.value.activeStack.last()
            val stacks = container.navigation.state.value.stacks
            assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
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
                assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
                compose.waitUntil {
                    compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex),
                        useUnmergedTree = true).fetchSemanticsNodes().size == 1
                }
                val retained = compose.onNodeWithText(target.text, useUnmergedTree = true)
                println("WP503_NATIVE_CHAT_WINDOW_AFTER|sdk=${android.os.Build.VERSION.SDK_INT}|size=$size" +
                    "|message=${target.id}|visibleMatches=${compose.onAllNodesWithText(target.text, useUnmergedTree = true).fetchSemanticsNodes().size}" +
                    "|entry=${entry.id}|draftSaved=true|timelineLoaded=true|realStore=true|realGraph=true|physicalTalkBack=false")
                retained.assertIsDisplayed()
                assertFont200Unclipped(retained)
                assertEquals("Recreation at $size must preserve the measured within-row pixel offset, not just its identity",
                    offsetOnly.first, captureViewport().first, 0.5f)
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
            println("WP503_NATIVE_FIXTURE_CLEANUP|sdk=${android.os.Build.VERSION.SDK_INT}|fixture=cached-resize" +
                "|phase=beforeDelete|seeded=$seeded|elapsedMs=${android.os.SystemClock.elapsedRealtime()}")
            if (seeded) runBlocking { container.processStore.deleteDeviceAndData(device.id) }
            println("WP503_NATIVE_FIXTURE_CLEANUP|sdk=${android.os.Build.VERSION.SDK_INT}|fixture=cached-resize" +
                "|phase=afterDelete|seeded=$seeded|elapsedMs=${android.os.SystemClock.elapsedRealtime()}")
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

    @Test fun realAppearancePersistsAllUnlockedThemesAndSchemesWithNativeFont200Actions() = withTouchExploration {
        val application = requireNotNull(activity.application as? MeshCoreApplication)
        val container = runBlocking { application.container.await() }
        val onboarding = requireNotNull(container.onboarding)
        val service = requireNotNull(container.themeService)
        val originalFlag = onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED)
        val originalSelection = runBlocking { service.refreshFromPreferences() }.selection
        assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
        assertEquals(ThemeRegistry.allThemes, service.availableToCurrentUser)
        assertEquals(10, service.availableToCurrentUser.size)
        val resources = activity.resources
        fun nativeCard(label: String): AccessibilityNodeInfo {
            val card = namedAction(root(), label)
            assertTrue("The actual named theme action must retain its Button role",
                descendants(card).any { it.className?.toString() == "android.widget.Button" })
            println("WP503_NATIVE_APPEARANCE_CARD|label=$label|enabled=${card.isEnabled}|clickable=${card.isClickable}")
            return card
        }
        fun nativeRadio(choice: AppColorSchemePreference): AccessibilityNodeInfo {
            val label = activity.getString(choice.labelResource)
            assertFont200Unclipped(compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed())
            val current = service.state.value
            assertTrue("Native radio readiness requires the actual ready preference owner", current is ThemeServiceState.Ready)
            val expectedChecked = (current as ThemeServiceState.Ready).selection.colorSchemePreference == choice
            var matches = emptyList<AccessibilityNodeInfo>()
            compose.waitUntil {
                val window = automation().rootInActiveWindow
                if (window?.packageName?.toString() != activity.packageName) return@waitUntil false
                assertTrue("Refresh the actual radio window before reading native checked state", window.refresh())
                matches = descendants(window).filter { it.text?.toString() == label }
                    .mapNotNull { named ->
                        generateSequence(named) { it.parent }.onEach {
                            assertTrue("Refresh the actual named radio ancestry", it.refresh())
                        }.firstOrNull { it.isCheckable }
                    }.distinct().toList()
                matches.size == 1 && matches.single().isChecked == expectedChecked
            }
            assertEquals("Exactly one named native $label radio must expose checked state", 1, matches.size)
            return matches.single().also {
                assertTrue(it.refresh())
                val bounds = Rect().also(it::getBoundsInScreen)
                println("WP503_NATIVE_APPEARANCE_RADIO|label=$label|checked=${it.isChecked}|checkable=${it.isCheckable}" +
                    "|bounds=$bounds|enabled=${it.isEnabled}|clickable=${it.isClickable}|actions=${it.actionList}")
            }
        }
        try {
            compose.runOnUiThread { onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, true) }
            compose.waitUntil { compose.onAllNodesWithTag("tab:CHATS").fetchSemanticsNodes().size == 1 }
            assertTrue(nativeTab(root(), AppTab.SETTINGS).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val appearance = activity.getString(AppSettingsStrings.appearanceTitle)
            compose.onNode(hasText(appearance) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).performScrollTo()
            assertFont200Unclipped(compose.onNodeWithText(appearance, useUnmergedTree = true))
            val action = namedAction(root(), appearance)
            assertNativeTargetContained(action, root())
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitForIdle()
            assertEquals(SettingsDetail.APPEARANCE, container.navigation.state.value.selectedSetting)
            assertFont200Unclipped(compose.onNodeWithTag("navigation-heading", useUnmergedTree = true))
            for (scheme in AppColorSchemePreference.entries) {
                val grid = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                grid.performScrollToIndex(0)
                val schemeAction = nativeRadio(scheme)
                appearanceTargetErrors.checkSucceeds { assertNativeTargetContained(schemeAction, root()) }
                if (!schemeAction.isChecked) assertTrue(schemeAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.waitUntil { (service.state.value as? ThemeServiceState.Ready)?.selection?.colorSchemePreference == scheme }
                compose.waitForIdle()
                for (choice in AppColorSchemePreference.entries) {
                    val label = activity.getString(choice.labelResource)
                    val radio = nativeRadio(choice)
                    assertEquals("The actual scheme radio checked state must be textual and exclusive", choice == scheme, radio.isChecked)
                    appearanceTargetErrors.checkSucceeds { assertNativeTargetContained(radio, root()) }
                    assertFont200Unclipped(compose.onNodeWithText(label, useUnmergedTree = true))
                }
                for ((index, theme) in service.availableToCurrentUser.withIndex()) {
                    grid.performScrollToIndex(index + 1)
                    val name = theme.localizedName(resources)
                    val selectedLabel = AppSettingsStrings.appearanceAccessibilityThemeCardSelectedLabel(resources, name)
                    val ownedLabel = AppSettingsStrings.appearanceAccessibilityThemeCardOwnedLabel(resources, name)
                    val current = service.state.value
                    assertTrue("The real theme service must remain ready", current is ThemeServiceState.Ready)
                    val wasSelected = (current as ThemeServiceState.Ready).selection.current.id == theme.id
                    val card = nativeCard(if (wasSelected) selectedLabel else ownedLabel)
                    assertNativeTargetContained(card, root())
                    if (!wasSelected) {
                        assertTrue("Every unselected theme must actually be unlocked", card.isEnabled)
                        assertTrue(card.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    }
                    compose.waitUntil { (service.state.value as? ThemeServiceState.Ready)?.selection?.current?.id == theme.id }
                    compose.waitForIdle()
                    val persisted = runBlocking { service.refreshFromPreferences() }.selection
                    assertEquals(theme.id, persisted.current.id)
                    assertEquals(scheme, persisted.colorSchemePreference)
                    assertEquals(theme.effectiveColorScheme(scheme), persisted.effectiveColorScheme)
                    compose.onNodeWithContentDescription(selectedLabel).assertIsDisplayed().assertIsNotEnabled()
                    val selectedCard = nativeCard(selectedLabel)
                    assertTrue("Selected state must disable duplicate selection", !selectedCard.isEnabled)
                    assertNativeTargetContained(selectedCard, root())
                    assertTrue(selectedCard.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                    assertTrue(selectedCard.refresh())
                    assertTrue(selectedCard.isAccessibilityFocused)
                    assertFont200Unclipped(compose.onNode(
                        hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                        useUnmergedTree = true,
                    ))
                    println("WP503_NATIVE_APPEARANCE|sdk=${android.os.Build.VERSION.SDK_INT}|theme=${theme.id.rawValue}" +
                        "|scheme=${scheme.rawValue}|effective=${persisted.effectiveColorScheme}|persisted=true" +
                        "|selectedText=true|duplicateDisabled=true|focus=true|cardTarget48=true|fontScale=2.0" +
                        "|realGraph=true|physicalTalkBack=false")
                }
            }
            assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
        } finally {
            runBlocking {
                service.setCurrent(originalSelection.current.id)
                service.setColorSchemePreference(originalSelection.colorSchemePreference)
                assertEquals(originalSelection, service.refreshFromPreferences().selection)
            }
            compose.runOnUiThread {
                if (container.navigation.state.value.selectedSetting == SettingsDetail.APPEARANCE) container.navigation.back()
                container.navigation.selectTab(AppTab.CHATS)
                onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, originalFlag)
            }
            assertEquals(originalFlag, onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED))
            println("WP503_NATIVE_APPEARANCE_RESTORED|theme=${originalSelection.current.id.rawValue}" +
                "|scheme=${originalSelection.colorSchemePreference.rawValue}|onboarding=$originalFlag")
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
        val (widthPixels, heightPixels) = size.split('x').map(String::toInt)
        shell("wm size $size")
        var resumed: MainActivity? = null
        var resumedWindowFocus: Boolean? = null
        var observation = "hostCount=notSampled"
        var previousObservation: String? = null
        try {
            compose.waitUntil {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val hosts = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>()
                    val states = mutableListOf<String>()
                    resumed = hosts.singleOrNull { host ->
                        val decor = host.window.decorView
                        val window = host.windowManager.currentWindowMetrics.bounds
                        val attached = decor.isAttachedToWindow
                        val laidOut = decor.isLaidOut
                        val focused = decor.hasWindowFocus()
                        val width = decor.width
                        val height = decor.height
                        // The calling fixtures assert native focus; this barrier only observes resized layout readiness.
                        val ready = attached && laidOut && width == widthPixels && height > 0 &&
                            window.width() == widthPixels && window.height() == heightPixels
                        if (ready) resumedWindowFocus = focused
                        states += "attached:$attached,laidOut:$laidOut,focused:$focused,decor:${width}x$height" +
                            ",metrics:${window.toShortString()},ready:$ready"
                        ready
                    }
                    observation = "hostCount=${hosts.size}|hosts=${states.joinToString(";")}"
                }
                if (observation != previousObservation) {
                    println("WP503_NATIVE_WINDOW_OBSERVATION|sdk=${android.os.Build.VERSION.SDK_INT}|size=$size" +
                        "|$observation|ready=${resumed != null}|physicalTalkBack=false")
                    previousObservation = observation
                }
                resumed != null
            }
        } catch (failure: ComposeTimeoutException) {
            println("WP503_NATIVE_WINDOW_TIMEOUT|sdk=${android.os.Build.VERSION.SDK_INT}|size=$size" +
                "|$observation|originalDeadlineMs=1000|physicalTalkBack=false")
            throw failure
        }
        assertTrue("The Compose rule must refer to the actual resumed resized Activity", activity === resumed)
        assertEquals(1, compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size)
        compose.onNodeWithTag(tag).assertIsDisplayed()
        println("WP503_NATIVE_WINDOW_READY|sdk=${android.os.Build.VERSION.SDK_INT}|size=$size" +
            "|resumed=true|laidOut=true|windowFocus=${requireNotNull(resumedWindowFocus)}" +
            "|semanticCount=1|semanticDisplayed=true|physicalTalkBack=false")
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

    @Test fun controlledPrimaryAndLateRestorationPreserveBothActualFailures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = requireNotNull(activity.getSystemService(AccessibilityManager::class.java))
        val originalFlags = automation().serviceInfo.flags
        var originalEnabled = false
        instrumentation.runOnMainSync { originalEnabled = manager.isTouchExplorationEnabled }
        val primary = AssertionError("WP503 controlled primary assertion")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val blockElapsed = java.util.concurrent.atomic.AtomicLong()
        var observed: AssertionError? = null
        println("WP503_NATIVE_CLEANUP_FAULT_ARM|requestedMainBlockMs=1500|originalDeadlineMs=1000" +
            "|faultInjectionOnly=true|physicalTalkBack=false")
        try {
            try {
                withTouchExploration {
                    assertTrue(android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val start = android.os.SystemClock.elapsedRealtime()
                        entered.countDown()
                        try {
                            release.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        } finally {
                            blockElapsed.set(android.os.SystemClock.elapsedRealtime() - start)
                            finished.countDown()
                        }
                    })
                    assertTrue("The controlled main fault must actually begin",
                        entered.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS))
                    throw primary
                }
            } catch (failure: AssertionError) {
                observed = failure
            }
            org.junit.Assert.assertSame("Cleanup must not replace the primary assertion", primary, observed)
            assertEquals(1, primary.suppressed.size)
            val cleanup = primary.suppressed.single()
            assertTrue("The unchanged real restoration waiter must time out",
                cleanup is ComposeTimeoutException)
            assertEquals("Native accessibility state did not become ready within1000ms", cleanup.message)
            assertTrue("The real main fault must be finished before the safety observation",
                finished.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertTrue("Fault injection must exceed, not extend, the original deadline", blockElapsed.get() >= 1000L)
            instrumentation.runOnMainSync {
                assertEquals(originalFlags, automation().serviceInfo.flags)
                assertEquals(originalEnabled, manager.isTouchExplorationEnabled)
            }
            println("WP503_NATIVE_CLEANUP_PRESERVED|primarySame=true|suppressed=1" +
                "|cleanup=${cleanup.javaClass.simpleName}|mainBlockMs=${blockElapsed.get()}" +
                "|flagsRestored=true|stateRestored=true|afterFailedDeadline=true" +
                "|faultInjectionOnly=true|physicalTalkBack=false")
        } finally {
            release.countDown()
        }
    }

    private fun withTouchExploration(block: () -> Unit) {
        val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val info = automation().serviceInfo
        println("WP503_NATIVE_FOCUS_SERVICE|sdk=${android.os.Build.VERSION.SDK_INT}|capabilities=${info.capabilities}" +
            "|flags=${info.flags}|touchExploration=${manager.isTouchExplorationEnabled}|physicalTalkBack=false")
        assertTrue("UiAutomation must actually support requesting touch exploration",
            info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION != 0)
        val original = info.flags
        val start = android.os.SystemClock.elapsedRealtime()
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            println("WP503_NATIVE_TOUCH_CALLBACK|enabled=$enabled|elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
        }
        println("WP503_NATIVE_TOUCH_LISTENER|registered=${manager.addTouchExplorationStateChangeListener(listener)}")
        val restoration = java.io.Closeable {
            println("WP503_NATIVE_TOUCH_FINAL|enabled=${manager.isTouchExplorationEnabled}|actualFlags=${automation().serviceInfo.flags}" +
                "|elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
            try {
                info.flags = original
                automation().serviceInfo = info
                val restoredTouch = original and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
                // Service-info writes acknowledge before the system dispatches the corresponding state change.
                val restorationStart = android.os.SystemClock.elapsedRealtime()
                var checks = 0
                var lastState: Pair<Int, Boolean>? = null
                var lastCheckElapsedMs = 0L
                try {
                    waitForNativeAccessibilityState(restorationStart) {
                        val flags = automation().serviceInfo.flags
                        val enabled = manager.isTouchExplorationEnabled
                        checks++
                        lastCheckElapsedMs = android.os.SystemClock.elapsedRealtime() - restorationStart
                        val state = flags to enabled
                        if (state != lastState) {
                            println("WP503_NATIVE_TOUCH_RESTORE_OBSERVATION|sdk=${android.os.Build.VERSION.SDK_INT}" +
                                "|actualFlags=$flags|enabled=$enabled|expectedFlags=$original|expectedEnabled=$restoredTouch" +
                                "|checks=$checks|waitElapsedMs=$lastCheckElapsedMs|physicalTalkBack=false")
                            lastState = state
                        }
                        flags == original && enabled == restoredTouch
                    }
                } catch (failure: ComposeTimeoutException) {
                    println("WP503_NATIVE_TOUCH_RESTORE_TIMEOUT|sdk=${android.os.Build.VERSION.SDK_INT}" +
                        "|lastObserved=$lastState|expectedFlags=$original|expectedEnabled=$restoredTouch" +
                        "|checks=$checks|lastCheckElapsedMs=$lastCheckElapsedMs" +
                        "|waitElapsedMs=${android.os.SystemClock.elapsedRealtime() - restorationStart}" +
                        "|originalDeadlineMs=1000|physicalTalkBack=false")
                    java.io.Closeable {
                        snapshotNativeServiceState(manager, "launcher-restoration-timeout", afterOriginalDeadline = true)
                    }.use { throw failure }
                }
                println("WP503_NATIVE_TOUCH_RESTORED|flags=$original|enabled=${manager.isTouchExplorationEnabled}" +
                    "|elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}|beforeNextFixture=true")
            } finally {
                manager.removeTouchExplorationStateChangeListener(listener)
            }
        }
        restoration.use {
            info.flags = original or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            automation().serviceInfo = info
            println("WP503_NATIVE_TOUCH_REQUEST|requestedFlags=${info.flags}|actualFlags=${automation().serviceInfo.flags}" +
                "|elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
            val activationStart = android.os.SystemClock.elapsedRealtime()
            var checks = 0
            var lastEnabled: Boolean? = null
            var lastCheckElapsedMs = 0L
            try {
                waitForNativeAccessibilityState(activationStart) {
                    val enabled = manager.isTouchExplorationEnabled
                    checks++
                    lastCheckElapsedMs = android.os.SystemClock.elapsedRealtime() - activationStart
                    if (enabled != lastEnabled) {
                        println("WP503_NATIVE_TOUCH_OBSERVATION|sdk=${android.os.Build.VERSION.SDK_INT}|enabled=$enabled" +
                            "|checks=$checks|waitElapsedMs=$lastCheckElapsedMs" +
                            "|helperElapsedMs=${android.os.SystemClock.elapsedRealtime() - start}|physicalTalkBack=false")
                        lastEnabled = enabled
                    }
                    enabled
                }
            } catch (failure: ComposeTimeoutException) {
                println("WP503_NATIVE_TOUCH_TIMEOUT|sdk=${android.os.Build.VERSION.SDK_INT}|lastObserved=$lastEnabled" +
                    "|currentEnabled=${manager.isTouchExplorationEnabled}|checks=$checks|lastCheckElapsedMs=$lastCheckElapsedMs" +
                    "|waitElapsedMs=${android.os.SystemClock.elapsedRealtime() - activationStart}" +
                    "|originalDeadlineMs=1000|physicalTalkBack=false")
                java.io.Closeable {
                    snapshotNativeServiceState(manager, "launcher-activation-timeout", afterOriginalDeadline = true)
                }.use { throw failure }
            }
            println("WP503_NATIVE_TOUCH_READY|sdk=${android.os.Build.VERSION.SDK_INT}|checks=$checks" +
                "|waitElapsedMs=${android.os.SystemClock.elapsedRealtime() - activationStart}|physicalTalkBack=false")
            println("WP503_NATIVE_FOCUS_SERVICE_ACTIVE|sdk=${android.os.Build.VERSION.SDK_INT}" +
                "|touchExploration=${manager.isTouchExplorationEnabled}|physicalTalkBack=false")
            block()
        }
    }

    private fun waitForNativeAccessibilityState(start: Long, condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var checks = 0
        while (true) {
            // AccessibilityManager applies binder state changes on the main looper.
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            checks++
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            if (ready && elapsed <= 1000L) {
                println("WP503_NATIVE_MAIN_OBSERVER|ready=true|checks=$checks|elapsedMs=$elapsed" +
                    "|originalDeadlineMs=1000|physicalTalkBack=false")
                return
            }
            if (elapsed >= 1000L) {
                println("WP503_NATIVE_MAIN_OBSERVER|ready=$ready|checks=$checks|elapsedMs=$elapsed" +
                    "|originalDeadlineMs=1000|result=TIMEOUT_NOT_PASS|physicalTalkBack=false")
                throw ComposeTimeoutException("Native accessibility state did not become ready within1000ms")
            }
            Thread.sleep(minOf(10L, 1000L - elapsed))
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
