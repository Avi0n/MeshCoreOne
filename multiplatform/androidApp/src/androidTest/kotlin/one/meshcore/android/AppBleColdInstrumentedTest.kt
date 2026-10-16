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
class AppBleColdInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun seedSavedRadioAndHoldConnected() = runPhase("seed") { fixture ->
        fixture.launch()
        dismissWhatsNew()
        val owner = fixture.connectSeed()
        dismissDeviceTip()
        assertReadyUi()
        val qualification = InstrumentationRegistry.getArguments().getString(AppPrivateChannelQualification.ARGUMENT)
        if (qualification != null) {
            check(qualification == "true")
            AppPrivateChannelQualification().verify(owner, fixture.checkpoints)
        }
        fixture.holdSeed()
    }

    @Test
    fun freshProcessAutomaticallyReconnectsSavedRadio() = runPhase("reconnect") { fixture ->
        fixture.launch()
        val owner = fixture.ready()
        assertReadyUi()
        fixture.checkpoint("reconnect-ready", owner)
        AppBleQueueComposeProgress(compose).await {
            fixture.disconnect(owner, ::disconnectUi)
            fixture.checkpoint("reconnect-disconnected", null)
        }
    }

    @Test
    fun explicitDisconnectSuppressesFreshLaunchReconnect() = runPhase("suppressed") { fixture ->
        fixture.launch()
        fixture.assertSuppressed()
        fixture.checkpoint("suppressed-complete", null)
    }

    private fun runPhase(phase: String, action: suspend (AppBleColdFixture) -> Unit) = runBlocking {
        assumeTrue(
            "Requires the isolated authorized cold BLE driver",
            InstrumentationRegistry.getArguments().getString(AppBleColdCheckpoints.ARGUMENT) != null,
        )
        val fixture = AppBleColdFixture(phase)
        var primary: Throwable? = null
        try {
            fixture.prepare()
            action(fixture)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                fixture.close()
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    private fun dismissWhatsNew() {
        val label = runBlocking { getString(Res.string.whatsnew_whats_new_continue_button) }
        val button = hasText(label) and hasAnyAncestor(isDialog())
        compose.waitUntil(AppBleColdFixture.TIMEOUT_MS) {
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
        compose.waitUntil(AppBleColdFixture.TIMEOUT_MS) {
            compose.onAllNodes(hasText(title)).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        compose.onNode(hasText(ok)).performClick()
        compose.waitUntil(AppBleColdFixture.TIMEOUT_MS) {
            compose.onAllNodes(hasText(title)).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        statusVisible()
    }

    private fun statusVisible() {
        val status = runBlocking { getString(Res.string.settings_ble_status_accessibility_label) }
        compose.waitUntil(AppBleColdFixture.TIMEOUT_MS) {
            compose.onAllNodes(hasContentDescription(status))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
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

    private fun disconnectUi() {
        statusVisible()
        val status = runBlocking { getString(Res.string.settings_ble_status_accessibility_label) }
        val label = runBlocking { getString(Res.string.settings_ble_status_disconnect) }
        compose.onNode(hasContentDescription(status)).assertIsDisplayed().assertHasClickAction().performClick()
        compose.waitUntil(AppBleColdFixture.TIMEOUT_MS) {
            compose.onAllNodes(hasText(label)).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        compose.onNode(hasText(label)).assertIsDisplayed().assertHasClickAction().performClick()
    }
}
