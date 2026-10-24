// AndroidOnly: WP-317 Compile and source contracts for the optional WP-318 Advanced Settings section; not rendered UI evidence.
package com.meshcoreone.android.feature.settings.device

import androidx.compose.runtime.Composable
import com.meshcoreone.android.feature.settings.device.ui.AdvancedSettingsHolders
import com.meshcoreone.android.feature.settings.device.ui.AdvancedSettingsScreen
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The existing module has no Compose UI test runner; mounted flow assertions belong to the WP-318 app integration. */
class AdvancedSettingsHostContractTest {
    private val source = File(
        "src/main/kotlin/com/meshcoreone/android/feature/settings/device/ui/AdvancedSettingsScreen.kt",
    ).readText()

    @Test
    fun configurationSectionIsOptionalWithoutPlaceholderContent() {
        assertTrue(source.contains("nodeConfigurationSection: (@Composable () -> Unit)? = null"))
        assertEquals(1, Regex("""nodeConfigurationSection\?\.invoke\(\)""").findAll(source).count())
    }

    @Test
    fun configurationSectionRetainsTheSourcePositionBetweenBatteryAndDeviceActions() {
        val content = source.substringAfter("SettingsScreenFrame(").substringBefore("/** Link to the shared")
        val sections = listOf(
            "OfflineMapsSection(navigator)",
            "FloodScopeSection(holders.floodScope, navigator)",
            "ContactsSection(holders.contacts)",
            "StaleCleanupSection(holders.stale)",
            "TelemetrySection(holders.telemetry, navigator)",
            "DirectMessagesSection(holders.directMessages)",
            "BatteryCurveSection(holders.battery)",
            "nodeConfigurationSection?.invoke()",
            "DeviceActionsSection(holders.actions)",
            "IdentitySection(holders.regenerate, navigator)",
            "DangerZoneSection(holders.dangerZone, onDismissPage)",
            "DiagnosticsSection(holders.diagnostics, onShareFile)",
        )
        val positions = sections.map { section ->
            content.indexOf(section).also { assertTrue(it >= 0, "Missing section: $section") }
        }
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun sectionRemainsInsideTheExistingLifecycleAndScreenFrame() {
        val lifecycle = source.substringAfter("HolderLifecycle(").substringBefore("SettingsScreenFrame(")
        assertTrue(lifecycle.contains("page.start()"))
        assertTrue(lifecycle.contains("page.stop()"))
        assertTrue(lifecycle.contains("dangerZone.cancelPendingRemoval()"))
        assertTrue(source.contains("SettingsScreenFrame(res(AppSettingsStrings.advancedSettingsTitle), onDone)"))
    }
}

// Both incumbent callers and a WP-318 caller must compile against the same screen without a service dependency.
@Composable
private fun compileHostCallers(
    holders: AdvancedSettingsHolders,
    navigator: SettingsNavigator,
    section: @Composable () -> Unit,
) {
    AdvancedSettingsScreen(holders, navigator, false, {}, {}, null)
    AdvancedSettingsScreen(holders, navigator, false, {}, {}, null, nodeConfigurationSection = section)
}
