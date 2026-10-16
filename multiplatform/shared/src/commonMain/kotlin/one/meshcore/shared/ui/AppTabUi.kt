package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_tabs_chats
import one.meshcore.shared.resources.localizable_tabs_map
import one.meshcore.shared.resources.localizable_tabs_nodes
import one.meshcore.shared.resources.localizable_tabs_settings
import one.meshcore.shared.resources.localizable_tabs_tools
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The localized label for [tab], read from the generated Compose resources (no hardcoded strings). */
@Composable
internal fun tabLabel(tab: AppTab): String = stringResource(tabLabelResource(tab))

internal fun tabLabelResource(tab: AppTab): StringResource = when (tab) {
    AppTab.CHATS -> Res.string.localizable_tabs_chats
    AppTab.NODES -> Res.string.localizable_tabs_nodes
    AppTab.MAP -> Res.string.localizable_tabs_map
    AppTab.TOOLS -> Res.string.localizable_tabs_tools
    AppTab.SETTINGS -> Res.string.localizable_tabs_settings
}

/** Material icon for [tab]; [selected] switches filled vs outlined. */
internal fun tabIcon(tab: AppTab, selected: Boolean): ImageVector = AppIcons.tab(tab, selected)
