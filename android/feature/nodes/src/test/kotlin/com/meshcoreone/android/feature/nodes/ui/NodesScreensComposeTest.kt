// AndroidOnly: WP-311 Native API31/37 Compose semantics and narrow-window regressions.
package com.meshcoreone.android.feature.nodes.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Harness
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactPathActionsApi31ComposeTest : NodesScreensComposeTestBase.ContactPathActionsComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactPathActionsApi37ComposeTest : NodesScreensComposeTestBase.ContactPathActionsComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryUiApi31ComposeTest : NodesScreensComposeTestBase.DiscoveryScreenComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryUiApi37ComposeTest : NodesScreensComposeTestBase.DiscoveryScreenComposeTestBase()

abstract class NodesScreensComposeTestBase {
    protected lateinit var controller: ActivityController<ComponentActivity>

    @Before
    fun attachHost() {
        idleMainLooper()
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        controller.get().actionBar?.hide()
        controller.windowFocusChanged(true)
    }

    @After
    fun disposeHost() {
        controller.windowFocusChanged(false)
        controller.pause().stop().destroy()
        idleMainLooper()
    }

    protected fun mount(content: @Composable () -> Unit) {
        controller.get().runOnUiThread {
            controller.get().setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = controller.get().resources.displayMetrics.density,
                        fontScale = 2f,
                    ),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    MaterialTheme(content = content)
                }
            }
        }
        advanceFrames()
    }

    protected fun scrollToAction(label: String): SemanticsNode {
        var lastNode: SemanticsNode? = null
        var lastScrollResult: Boolean? = null
        repeat(20) {
            advanceFrames()
            actionNode(label)?.let { action ->
                lastNode = action
                if (isDisplayed(action)) return action
            }
            val scrollable = allSemanticsNodes().firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }
            val scroll = scrollable?.config?.getOrElseNullable(SemanticsActions.ScrollBy) { null }?.action
            assertNotNull(scroll, "A scroll action must remain available to reveal $label")
            controller.get().runOnUiThread { lastScrollResult = scroll.invoke(0f, 600f) }
        }
        val root = findComposeView(controller.get().window.decorView)
        val scrollState = allSemanticsNodes()
            .filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            .mapNotNull { it.config.getOrElseNullable(SemanticsProperties.VerticalScrollAxisRange) { null } }
            .joinToString { "${it.value.invoke()}/${it.maxValue.invoke()} reverse=${it.reverseScrolling}" }
        error(
            "$label never acquired visible Compose bounds; root=${root.width}x${root.height}, " +
                "target=${lastNode?.boundsInRoot}, scrollResult=$lastScrollResult, " +
                "scrollState=$scrollState, " +
                "nodes=${allSemanticsNodes().joinToString { "${nodeText(it)}@${it.boundsInRoot}" }}",
        )
    }

    protected fun awaitText(text: String): SemanticsNode {
        repeat(100) {
            Thread.sleep(10)
            advanceFrames()
            allSemanticsNodes().firstOrNull { nodeText(it).contains(text) }?.let { return it }
        }
        error(
            "Timed out waiting for '$text': " +
                allSemanticsNodes().joinToString { "${nodeText(it)}@${it.boundsInRoot}" },
        )
    }

    protected fun awaitAction(label: String): SemanticsNode {
        repeat(100) {
            Thread.sleep(10)
            advanceFrames()
            actionNode(label)?.let { return it }
        }
        error(
            "Timed out waiting for action '$label': " +
                allSemanticsNodes().joinToString { "${nodeText(it)}@${it.boundsInRoot}" },
        )
    }

    private fun actionNode(label: String): SemanticsNode? =
        allSemanticsNodes().firstOrNull { node ->
            node.config.contains(SemanticsActions.OnClick) && nodeText(node).any { it == label }
        }

    private fun nodeText(node: SemanticsNode): List<String> =
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }

    protected fun allSemanticsNodes(): List<SemanticsNode> {
        val view = findComposeView(controller.get().window.decorView)
        val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            result += node
            node.children.forEach(::visit)
        }
        visit(owner.rootSemanticsNode)
        return result
    }

    private fun findComposeView(view: View): View {
        return findComposeViewOrNull(view)
            ?: throw AssertionError("Compose root view is not attached")
    }

    private fun findComposeViewOrNull(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                findComposeViewOrNull(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    protected fun isDisplayed(node: SemanticsNode): Boolean {
        val root = findComposeView(controller.get().window.decorView)
        val bounds: Rect = node.boundsInRoot
        return bounds.width > 0f && bounds.height > 0f &&
            bounds.right > 0f && bounds.bottom > 0f &&
            bounds.left < root.width && bounds.top < root.height
    }

    protected fun currentDensity() = controller.get().resources.displayMetrics.density

    private fun advanceFrames() {
        controller.get().runOnUiThread { Snapshot.sendApplyNotifications() }
        idleMainLooper()
    }

    private fun idleMainLooper() {
        repeat(6) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    abstract class ContactPathActionsComposeTestBase : NodesScreensComposeTestBase() {
        @Test
        fun pathActionsRemainVisibleAndAtLeast48DpAtLargeTextInRtl() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val contact = Fixtures.contact(
                radioId = radio,
                type = ContactType.REPEATER,
                name = "Path target",
                outPathLength = 1u,
                outPath = Bytes(byteArrayOf(0x01)),
            )
            harness.store.contacts += contact

            mount { ContactDetailScreen(contact, harness.dependencies, NodesNavigation.NONE) }

            val context = controller.get()
            listOf(C.contactsDetailDiscoverPath, C.contactsDetailEditPath, C.contactsDetailResetPath)
                .map(context::getString)
                .forEach { label ->
                    val action = scrollToAction(label)
                    assertTrue(isDisplayed(action), "$label must have visible bounds after scrolling")
                    assertTrue(action.boundsInRoot.height / currentDensity() >= 48f, "$label must retain a 48dp target")
                    assertFalse(action.config.contains(SemanticsProperties.Disabled), "$label must remain enabled")
                }
        }

        @Test
        fun disconnectedPathActionsRemainVisibleAndDisabled() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val contact = Fixtures.contact(
                radioId = radio,
                type = ContactType.REPEATER,
                name = "Offline path target",
                outPathLength = 1u,
                outPath = Bytes(byteArrayOf(0x01)),
            )
            harness.store.contacts += contact
            harness.session.state = DeviceConnectionState.DISCONNECTED
            harness.session.device = null
            harness.session.contacts = null
            harness.session.adverts = null
            harness.session.trace = null

            mount { ContactDetailScreen(contact, harness.dependencies, NodesNavigation.NONE) }

            val context = controller.get()
            listOf(C.contactsDetailDiscoverPath, C.contactsDetailEditPath, C.contactsDetailResetPath)
                .map(context::getString)
                .forEach { label ->
                    val action = scrollToAction(label)
                    assertTrue(isDisplayed(action), "$label must remain visible while disconnected")
                    assertTrue(action.config.contains(SemanticsProperties.Disabled), "$label must be disabled offline")
                    assertTrue(action.boundsInRoot.height / currentDensity() >= 48f, "$label must retain a 48dp target")
                }
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(harness.adverts.syncingFlags.isEmpty())
        }
    }

    abstract class DiscoveryScreenComposeTestBase : NodesScreensComposeTestBase() {
        @Test
        fun disconnectedDiscoveryShowsCachedOfflineResultInsteadOfProgress() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.store.discovered += Fixtures.node(radioId = radio, name = "Cached offline node")
            harness.session.state = DeviceConnectionState.DISCONNECTED
            harness.session.device = null

            mount { DiscoveryScreen(harness.dependencies) }

            assertTrue(isDisplayed(awaitText("Cached offline node")))
            assertFalse(
                allSemanticsNodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) },
            )
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(harness.adverts.syncingFlags.isEmpty())
            assertTrue(harness.trace.sent.isEmpty())

            val context = controller.get()
            listOf(C.contactsDiscoveryAdd, C.contactsDiscoveryRemove).map(context::getString).forEach { label ->
                val action = awaitAction(label)
                assertTrue(isDisplayed(action), "$label must remain visible at 200% font scale")
                assertTrue(action.boundsInRoot.height / currentDensity() >= 48f, "$label must retain a 48dp target")
            }
        }
    }
}
