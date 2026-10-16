package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_select_setting
import one.meshcore.shared.resources.settings_title
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.LocalShellContentRole
import one.meshcore.shared.ui.PlaceholderPane
import one.meshcore.shared.ui.ShellContentRole
import org.jetbrains.compose.resources.stringResource

/**
 * Settings hub: Material preference list of primary destinations. Nested pages (Device Info, Radio,
 * Advanced, Connection, Location, Backup, Appearance, Support, Chats, Notifications, Offline Maps)
 * are live screens, not placeholders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHubScreen(
    selected: SettingsDestination?,
    onSelect: (SettingsDestination?) -> Unit,
    inputs: SettingsHubDetailInputs,
    onConnectDevice: () -> Unit = {},
    appVersion: String? = null,
    appBuild: String? = null,
    modifier: Modifier = Modifier,
) {
    val contentRole = LocalShellContentRole.current
    // Hosted here (not on the list) so compact list→detail→back keeps the hub scroll offset.
    // rememberSaveable also restores after the Settings tab leaves composition.
    val hubListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val showList = contentRole == ShellContentRole.WideList ||
        (contentRole == ShellContentRole.CompactStacked && selected == null)
    val showDetail = contentRole == ShellContentRole.WideDetail ||
        (contentRole == ShellContentRole.CompactStacked && selected != null)

    if (showList) {
        SettingsHubList(
            inputs = inputs,
            selected = if (contentRole == ShellContentRole.WideList) selected else null,
            onSelect = onSelect,
            listState = hubListState,
            onConnectDevice = onConnectDevice,
            appVersion = appVersion,
            appBuild = appBuild,
            modifier = modifier,
        )
        return
    }

    if (!showDetail || selected == null) {
        PlaceholderPane(
            title = stringResource(Res.string.settings_select_setting),
            subtitle = stringResource(Res.string.settings_title),
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            // Compact and wide-detail both keep up-navigation so system back and toolbar match.
            AppNestedTopBar(
                title = destinationTitle(selected),
                onBack = { onSelect(selected.backParent) },
            )
        },
    ) { innerPadding ->
        Box(Modifier.consumeWindowInsets(innerPadding)) {
            SettingsHubDetail(
                selected = selected,
                inputs = inputs,
                innerPadding = innerPadding,
                onSelect = onSelect,
            )
        }
    }
}
