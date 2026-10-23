// AndroidOnly: WP-503 Observe actual process-backed About routes, packaged notices and non-gesture reading at system font200.
package com.meshcoreone.android.accessibility

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.feature.onboarding.OnboardingState
import com.meshcoreone.android.feature.settings.app.about.AboutLinks
import com.meshcoreone.android.feature.settings.app.about.AboutRow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSettingsNoticesAccessibilityTest : DeviceAccessibilityHarness() {
    private val intents = ConcurrentLinkedQueue<Intent>()
    private val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
            check(intent.action == Intent.ACTION_VIEW) { "Unexpected external activity in native About audit: ${intent.action}" }
            intents.add(Intent(intent))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }
    private val about get() = activity.getString(AppSettingsStrings.aboutHeader)
    private val licenses get() = activity.getString(com.meshcoreone.android.R.string.platform_open_source_licenses)
    private val done get() = activity.getString(AppLocalizableStrings.commonDone)

    @Before fun captureActualExternalIntentsWithoutOpeningAnotherApp() {
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
    }

    @After fun removeOwnedMonitor() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
        check(intents.isEmpty()) { "Every captured native external Intent must have an exact-payload assertion" }
    }

    @Test fun actualAboutLinksAndEveryPackagedNoticeKeepNamedContained48dpActionsAtFont200() = withSettings {
        openAbout()
        AboutLinks.rows.forEach { row ->
            val label = activity.getString(row.labelResource)
            val action = action(label)
            if (row is AboutRow.ExternalLink) {
                action.performClick()
                val intent = requireNotNull(intents.poll())
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals(row.url, intent.dataString)
                println("WP503_NOTICES_EXTERNAL|label=$label|url=${intent.dataString}|actualIntent=true|browserLaunched=false")
            }
        }
        action(licenses).performClick()
        val paths = bundledNotices("licenses").sorted()
        assertTrue(paths.containsAll(listOf("licenses/GPL-3.0.txt", "licenses/MeshCore-MIT.txt")))
        paths.forEach { path -> noticeAction(path) }
        action(done).performClick()
        action(about)
        println("WP503_NOTICES_CATALOG|actualPackagedRows=${paths.size}|externalIntents=4|returnedToSettings=true|legalApproval=false")
    }

    @Test fun actualGplAndMeshCoreNoticesExposeEveryLineToNativeNonGestureScrollAndReturnActions() = withSettings {
        openAbout()
        action(licenses).performClick()
        for ((path, useBack) in listOf("licenses/GPL-3.0.txt" to true, "licenses/MeshCore-MIT.txt" to false)) {
            noticeAction(path).performClick()
            val text = activity.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue(text.isNotBlank())
            val matcher = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
            compose.waitUntil { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            assertEveryNoticeLineReachable(path, compose.onNode(matcher, useUnmergedTree = true))
            action(done)
            if (useBack) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            else action(done).performClick()
            noticeAction(path)
            println("WP503_NOTICES_RETURN|path=$path|nativeBack=$useBack|returnedToCatalog=true")
        }
        action(done).performClick()
        action(about)
    }

    private fun withSettings(block: () -> Unit) {
        val application = requireNotNull(activity.application as? MeshCoreApplication)
        val container = runBlocking { application.container.await() }
        val onboarding = requireNotNull(container.onboarding)
        val originalFlag = onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED)
        val stacks = container.navigation.state.value.stacks
        try {
            compose.runOnUiThread { onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, true) }
            compose.waitUntil { compose.onAllNodesWithTag("tab:SETTINGS").fetchSemanticsNodes().size == 1 }
            assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
            compose.onNodeWithTag("tab:SETTINGS").performClick()
            block()
            assertEquals(AppTab.SETTINGS, container.navigation.state.value.selectedTab)
            assertEquals(null, container.navigation.state.value.selectedSetting)
            assertEquals(stacks, container.navigation.state.value.stacks)
            assertEquals(DeviceConnectionState.DISCONNECTED, container.connectionManager.connectionState)
            println("WP503_NOTICES_GRAPH|realProcessGraph=true|disconnected=true|stacksUnchanged=true|physicalTalkBack=false")
        } finally {
            compose.runOnUiThread {
                container.navigation.selectTab(AppTab.CHATS)
                onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, originalFlag)
            }
        }
    }

    private fun openAbout() = action(about).performClick()

    private fun bundledNotices(parent: String): List<String> = requireNotNull(activity.assets.list(parent)).flatMap { name ->
        val path = "$parent/$name"
        if (name.endsWith(".txt")) listOf(path) else bundledNotices(path)
    }

    private fun noticeLabel(path: String) = path.removePrefix("licenses/").removeSuffix(".txt")

    private fun noticeAction(path: String): SemanticsNodeInteraction {
        val list = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)
        compose.waitUntil { compose.onAllNodes(list).fetchSemanticsNodes().size == 1 }
        compose.onNode(list).performScrollToNode(hasText(noticeLabel(path)) and hasClickAction())
        return action(noticeLabel(path))
    }

    private fun scrollParent(node: SemanticsNode) = generateSequence(node.parent) { it.parent }.firstOrNull {
        it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
    }

    private fun viewport(node: SemanticsNode): Rect {
        val safe = nativeWindowViewport(compose, activity.window.decorView, node)
        return scrollParent(node)?.let { safe.intersect(it.boundsInWindow) } ?: safe
    }

    private fun scroll(node: SemanticsNode, delta: Float) {
        val parent = requireNotNull(scrollParent(node)) { "Clipped native notice content must have a non-gesture scroll action" }
        compose.onNode(SemanticsMatcher("Actual notices scroll viewport") { it.id == parent.id }, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, delta)) }
    }

    private fun action(label: String): SemanticsNodeInteraction {
        val interaction = compose.onNode(hasText(label) and hasClickAction())
        val initial = interaction.fetchSemanticsNode()
        if (scrollParent(initial) != null) interaction.performScrollTo()
        val before = interaction.fetchSemanticsNode()
        val safe = viewport(before)
        val offset = before.boundsInWindow.topLeft - before.boundsInRoot.topLeft
        val top = before.positionInRoot.y + offset.y
        val bottom = top + before.size.height
        val delta = if (bottom > safe.bottom) bottom - safe.bottom else if (top < safe.top) top - safe.top else 0f
        if (delta != 0f) scroll(before, delta)
        interaction.assertIsDisplayed()
        val text = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true)
        assertFont200Unclipped(text)
        val node = interaction.fetchSemanticsNode()
        val actual = viewport(node)
        val target = node.touchBoundsInRoot.translate(node.boundsInWindow.topLeft - node.boundsInRoot.topLeft)
        val density = activity.resources.displayMetrics.density
        println("WP503_NOTICES_TARGET|label=$label|bounds=$target|viewport=$actual")
        assertTrue("Native About/license targets must be wholly visible and at least48dp",
            target.left >= actual.left && target.top >= actual.top && target.right <= actual.right && target.bottom <= actual.bottom &&
                target.width / density >= 48f && target.height / density >= 48f)
        return interaction
    }

    private fun assertEveryNoticeLineReachable(path: String, interaction: SemanticsNodeInteraction) {
        val layout = textLayout(interaction)
        assertEquals(2f, layout.layoutInput.density.fontScale, 0f)
        assertEquals(LayoutDirection.Rtl, layout.layoutInput.layoutDirection)
        assertUnclippedContent(layout)
        var nextLine = 0
        var windows = 0
        while (nextLine < layout.lineCount) {
            val node = interaction.fetchSemanticsNode()
            val safe = viewport(node)
            val origin = node.positionInRoot + node.boundsInWindow.topLeft - node.boundsInRoot.topLeft
            val start = nextLine
            while (nextLine < layout.lineCount && origin.y + layout.getLineBottom(nextLine) <= safe.bottom) {
                assertTrue("Every packaged notice line must be fully inside its actual native viewport",
                    origin.y + layout.getLineTop(nextLine) >= safe.top &&
                        origin.x + layout.getLineLeft(nextLine) >= safe.left &&
                        origin.x + layout.getLineRight(nextLine) <= safe.right)
                nextLine++
            }
            assertTrue("Non-gesture scrolling must expose at least one new complete notice line", nextLine > start)
            windows++
            println("WP503_NOTICES_WINDOW|path=$path|fromLine=$start|throughLine=${nextLine - 1}|viewport=$safe|origin=$origin")
            if (nextLine < layout.lineCount) scroll(node, origin.y + layout.getLineTop(nextLine) - safe.top)
        }
        println("WP503_NOTICES_READ|path=$path|lines=$nextLine|windows=$windows|everyLineContained=true|fontScale=2.0|realPackagedText=true")
    }
}
