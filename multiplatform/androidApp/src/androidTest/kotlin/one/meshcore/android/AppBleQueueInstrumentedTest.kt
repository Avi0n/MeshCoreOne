package one.meshcore.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_tip_device_menu_title
import one.meshcore.shared.resources.localizable_common_ok
import one.meshcore.shared.resources.settings_ble_status_accessibility_hint_connected
import one.meshcore.shared.resources.settings_ble_status_accessibility_label
import one.meshcore.shared.resources.settings_ble_status_disconnect
import one.meshcore.shared.resources.settings_ble_status_status_ready
import one.meshcore.shared.resources.whatsnew_whats_new_continue_button
import org.jetbrains.compose.resources.getString
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppBleQueueInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun defaultBleQueueRecreatesRetiresAndReplaces() = runBlocking {
        assumeTrue(
            "Requires the isolated authorized BLE queue driver",
            InstrumentationRegistry.getArguments().getString(AppBleQueueCheckpoints.ARGUMENT) != null,
        )
        val fixture = AppBleQueueFixture()
        val progress = AppBleQueueComposeProgress(compose)
        var failure: Throwable? = null
        try {
            fixture.verifyFailureObservation()
            progress.verifyRoomControl()
            fixture.open()
            dismissWhatsNew()
            val first = fixture.connect()
            dismissDeviceTip()
            assertReadyUi()
            fixture.checkpoint("first-ready", first)
            fixture.recreate(first)
            assertReadyUi()
            fixture.checkpoint("recreated", first)
            progress.await {
                fixture.disconnect(first, ::disconnectUi)
                fixture.checkpoint("first-disconnected", null)
            }
            val second = fixture.connect()
            fixture.assertReplacement(first, second)
            assertReadyUi()
            fixture.checkpoint("second-ready", second)
            progress.await {
                fixture.disconnect(second, ::disconnectUi)
                fixture.checkpoint("complete", null)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                fixture.close()
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }

    private fun dismissWhatsNew() {
        val label = runBlocking { getString(Res.string.whatsnew_whats_new_continue_button) }
        val button = hasText(label) and hasAnyAncestor(isDialog())
        compose.waitUntil(AppBleQueueFixture.TIMEOUT_MS) {
            val dialogs = compose.onAllNodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false)
            val matches = compose.onAllNodes(button).fetchSemanticsNodes(atLeastOneRootRequired = false)
            if (matches.size == 1) compose.onNode(button).performClick()
            dialogs.isEmpty()
        }
        statusVisible()
    }

    private fun dismissDeviceTip() {
        val title = runBlocking { getString(Res.string.chats_tip_device_menu_title) }
        val ok = runBlocking { getString(Res.string.localizable_common_ok) }
        compose.waitUntil(AppBleQueueFixture.TIMEOUT_MS) {
            compose.onAllNodes(hasText(title)).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        compose.onNode(hasText(ok)).performClick()
        compose.waitUntil(AppBleQueueFixture.TIMEOUT_MS) {
            compose.onAllNodes(hasText(title)).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        statusVisible()
    }

    private fun statusVisible() {
        val status = runBlocking { getString(Res.string.settings_ble_status_accessibility_label) }
        compose.waitUntil(AppBleQueueFixture.TIMEOUT_MS) {
            compose.onAllNodes(
                hasContentDescription(status),
            ).fetchSemanticsNodes(atLeastOneRootRequired = false).size ==
                1
        }
    }

    private fun assertReadyUi() {
        statusVisible()
        val status = runBlocking { getString(Res.string.settings_ble_status_accessibility_label) }
        val ready = runBlocking { getString(Res.string.settings_ble_status_status_ready) }
        val hint = runBlocking { getString(Res.string.settings_ble_status_accessibility_hint_connected) }
        compose.onNode(hasContentDescription(status)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$ready. $hint"),
        )
    }

    private fun disconnectUi(report: (String) -> Unit) {
        report("status-visible-await")
        statusVisible()
        val status = runBlocking { getString(Res.string.settings_ble_status_accessibility_label) }
        val label = runBlocking { getString(Res.string.settings_ble_status_disconnect) }
        report("status-actionable-assert")
        compose.onNode(hasContentDescription(status)).assertIsDisplayed().assertHasClickAction()
        report("status-click-entry")
        compose.onNode(hasContentDescription(status)).performClick()
        report("status-click-returned")
        compose.waitUntil(AppBleQueueFixture.TIMEOUT_MS) {
            compose.onAllNodes(hasText(label)).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        report("disconnect-actionable-assert")
        compose.onNode(hasText(label)).assertIsDisplayed().assertHasClickAction()
        report("disconnect-click-entry")
        compose.onNode(hasText(label)).performClick()
        report("disconnect-click-returned")
    }
}
