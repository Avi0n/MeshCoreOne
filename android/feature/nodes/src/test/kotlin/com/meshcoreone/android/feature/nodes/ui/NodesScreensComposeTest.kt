// AndroidOnly: WP-311 Native API31/37 Compose semantics and narrow-window regressions.
package com.meshcoreone.android.feature.nodes.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.share.ContactQrShareStateHolder
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Harness
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
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
import org.robolectric.shadows.ShadowDialog
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactShareApi31ComposeTest : NodesScreensComposeTestBase.ContactShareComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactShareApi37ComposeTest : NodesScreensComposeTestBase.ContactShareComposeTestBase()

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
        val root = findComposeView(rootView())
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
            allSemanticsNodes().firstOrNull { nodeText(it).contains(text) && isDisplayed(it) }?.let { return it }
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

    protected fun actionNode(label: String): SemanticsNode? =
        allSemanticsNodes().firstOrNull { node ->
            node.config.contains(SemanticsActions.OnClick) && nodeText(node).any { it == label }
        }

    private fun nodeText(node: SemanticsNode): List<String> =
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }

    protected fun allSemanticsNodes(): List<SemanticsNode> {
        val view = findComposeView(rootView())
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
        val root = findComposeView(rootView())
        val bounds: Rect = node.boundsInRoot
        return bounds.width > 0f && bounds.height > 0f &&
            bounds.right > 0f && bounds.bottom > 0f &&
            bounds.left < root.width && bounds.top < root.height
    }

    protected fun currentDensity() = controller.get().resources.displayMetrics.density

    protected open fun rootView(): View = controller.get().window.decorView

    protected fun advanceFrames() {
        controller.get().runOnUiThread { Snapshot.sendApplyNotifications() }
        idleMainLooper()
        controller.get().runOnUiThread {
            val view = findComposeView(rootView())
            if (view.width > 0 && view.height > 0) {
                view.forceLayout()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY),
                )
                view.layout(view.left, view.top, view.right, view.bottom)
            }
        }
    }

    protected fun click(node: SemanticsNode) {
        val action = node.config[SemanticsActions.OnClick].action
        assertNotNull(action)
        controller.get().runOnUiThread { assertTrue(action.invoke()) }
        advanceFrames()
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
        override fun rootView(): View =
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: super.rootView()

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
            val add = awaitAction(context.getString(C.contactsDiscoveryAdd))
            assertFalse(add.config.contains(SemanticsProperties.Disabled))
            click(add)
            assertTrue(
                isDisplayed(awaitText(context.getString(C.contactsAddErrorNotConnected))),
                "Offline Add must explain recovery instead of silently succeeding",
            )
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(harness.store.contacts.isEmpty())
            assertEquals(listOf("Cached offline node"), harness.store.discovered.map { it.name })
            click(awaitAction(context.getString(C.contactsCommonOk)))
            val remove = awaitAction(context.getString(C.contactsDiscoveryRemove))
            assertFalse(remove.config.contains(SemanticsProperties.Disabled))
            click(remove)
            assertTrue(isDisplayed(awaitText(context.getString(C.contactsDiscoveryEmptyTitle))))
            assertTrue(harness.store.discovered.isEmpty())
            assertTrue(harness.contactService.calls.isEmpty())
        }

        @Test
        fun connectedDiscoveryAddUsesTheLiveServiceAndReloadsAddedState() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val node = Fixtures.node(radioId = radio, name = "Ready relay")
            harness.store.discovered += node
            mount { DiscoveryScreen(harness.dependencies) }
            val context = controller.get()
            val add = awaitAction(context.getString(C.contactsDiscoveryAdd))
            assertFalse(add.config.contains(SemanticsProperties.Disabled))
            assertTrue(isDisplayed(add))
            assertTrue(add.boundsInRoot.height / currentDensity() >= 48f)
            click(add)
            assertTrue(isDisplayed(awaitText(context.getString(C.contactsDiscoveryAdded))))
            assertEquals(listOf("add:Ready relay"), harness.contactService.calls)
            assertEquals(node.publicKey, harness.store.contacts.single().publicKey)
            assertEquals(radio, harness.store.contacts.single().radioId)
            assertTrue(harness.adverts.syncingFlags.isEmpty())
            assertTrue(harness.trace.sent.isEmpty())
        }

        @Test
        fun disconnectAfterMountRechecksAddCapabilityBeforeAnyRadioCall() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.store.discovered += Fixtures.node(radioId = radio, name = "Was ready")
            mount { DiscoveryScreen(harness.dependencies) }
            val context = controller.get()
            val add = awaitAction(context.getString(C.contactsDiscoveryAdd))
            harness.session.contacts = null
            harness.session.device = null
            harness.session.state = DeviceConnectionState.DISCONNECTED
            click(add)
            assertTrue(isDisplayed(awaitText(context.getString(C.contactsAddErrorNotConnected))))
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(harness.store.contacts.isEmpty())
        }
    }

    abstract class ContactShareComposeTestBase : NodesScreensComposeTestBase() {
        override fun rootView(): View = checkNotNull(ShadowDialog.getLatestDialog().window).decorView

        @Test
        fun qrCopyKeepsFullTextTargetFeedbackClipboardChooserAndDismissal() {
            val context = controller.get()
            val configuration = Configuration(context.resources.configuration).apply {
                fontScale = 2f
                setLayoutDirection(java.util.Locale.forLanguageTag("ar"))
            }
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
            val harness = Harness()
            val key = Fixtures.repeated(0xAB)
            val showing = mutableStateOf(true)
            lateinit var holder: ContactQrShareStateHolder
            mount {
                val scope = rememberCoroutineScope()
                if (showing.value) {
                    holder = remember {
                        ContactQrShareStateHolder("Offline relay", key, ContactType.REPEATER, harness.dependencies, scope)
                    }
                    ContactShareDialog(holder) { showing.value = false }
                }
            }
            val copy = awaitAction(context.getString(C.contactsQrCopy))
            assertEquals(2f, context.resources.configuration.fontScale)
            assertEquals(View.LAYOUT_DIRECTION_RTL, context.resources.configuration.layoutDirection)
            assertTrue(
                allSemanticsNodes().any { it.config.contains(SemanticsActions.ScrollBy) },
                "QR content must expose non-gesture scroll recovery when the window is constrained",
            )
            assertTrue(
                generateSequence(copy.parent) { it.parent }.none { it.config.contains(SemanticsActions.ScrollBy) },
                "Copy must not be clipped by the QR content scroll viewport",
            )
            val publicKey = awaitText(holder.publicKeyText)
            assertEquals(publicKey.size.height.toFloat(), publicKey.boundsInRoot.height, "Public key must be fully visible")
            assertEquals(publicKey.size.width.toFloat(), publicKey.boundsInRoot.width, "Public key must not clip horizontally")
            assertTrue(copy.boundsInRoot.height / currentDensity() >= 48f, "Copy must retain a 48dp height: ${copy.boundsInRoot}")
            assertTrue(copy.boundsInRoot.width / currentDensity() >= 48f, "Copy must retain a 48dp width: ${copy.boundsInRoot}")
            assertTrue(copy.boundsInRoot.top >= 0f && copy.boundsInRoot.bottom <= rootView().height)
            assertTrue(copy.boundsInRoot.left >= 0f && copy.boundsInRoot.right <= rootView().width)
            val layouts = mutableListOf<TextLayoutResult>()
            val textLayout = copy.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null }?.action
            assertNotNull(textLayout, "Copy must retain measurable text")
            context.runOnUiThread { textLayout.invoke(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach {
                repeat(it.lineCount) { line ->
                    assertTrue(it.getLineTop(line) >= 0f)
                    assertTrue(it.getLineBottom(line) <= it.size.height, "Copy line $line must be fully allocated")
                    assertTrue(
                        it.getLineRight(line) - it.getLineLeft(line) <= copy.boundsInRoot.width,
                        "Copy line $line must fit within the action",
                    )
                }
            }
            click(copy)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(key.hexString.uppercase(), clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            val copied = awaitAction(context.getString(C.contactsQrCopied))
            assertTrue(copied.config.contains(SemanticsProperties.Disabled))
            runBlocking { harness.clock.advanceBy(1.seconds) }
            advanceFrames()
            assertNotNull(actionNode(context.getString(C.contactsQrCopied)))
            runBlocking { harness.clock.advanceBy(1.seconds) }
            val reset = awaitAction(context.getString(C.contactsQrCopy))
            assertFalse(reset.config.contains(SemanticsProperties.Disabled))
            click(scrollToAction(context.getString(C.contactsDetailShareContact)))
            val chooser = Shadows.shadowOf(context).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertEquals(holder.contactName, chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(send)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(holder.contactUri, send.getStringExtra(Intent.EXTRA_TEXT))
            val shareSubject = context.getString(C.contactsQrShareSubject)
            assertFalse(shareSubject == holder.contactName, "Fixture must distinguish the localized subject from the contact name")
            assertEquals(shareSubject, send.getStringExtra(Intent.EXTRA_SUBJECT))
            click(scrollToAction(context.getString(C.contactsCommonDone)))
            assertFalse(showing.value)
        }

    }
}
