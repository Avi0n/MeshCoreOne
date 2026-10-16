package one.meshcore.shared.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_demo_mode_enabled
import one.meshcore.shared.resources.settings_demo_mode_footer
import one.meshcore.shared.resources.settings_demo_mode_header
import one.meshcore.shared.resources.settings_demo_mode_screenshot
import one.meshcore.shared.settings.DemoModeStore
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DemoModeSection(demoMode: DemoModeStore) {
    val unlocked by demoMode.isUnlocked.collectAsStateWithLifecycle()
    val enabled by demoMode.isEnabled.collectAsStateWithLifecycle()
    val screenshot by demoMode.isScreenshotModeEnabled.collectAsStateWithLifecycle()
    if (!unlocked) return
    PreferenceHubSection(
        title = stringResource(Res.string.settings_demo_mode_header),
        applyHorizontalPadding = false,
    ) {
        SettingsToggleRow(
            label = stringResource(Res.string.settings_demo_mode_enabled),
            checked = enabled,
            onCheckedChange = { demoMode.setEnabled(it) },
        )
        SettingsToggleRow(
            label = stringResource(Res.string.settings_demo_mode_screenshot),
            checked = screenshot,
            onCheckedChange = { demoMode.setScreenshotModeEnabled(it) },
        )
    }
    PreferenceHubFooter(
        stringResource(Res.string.settings_demo_mode_footer),
        applyHorizontalPadding = false,
    )
}
