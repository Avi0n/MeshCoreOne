package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_empty_state_select_conversation
import one.meshcore.shared.resources.contacts_list_select_node
import one.meshcore.shared.resources.settings_select_setting
import one.meshcore.shared.resources.tools_select_tool
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun emptyDetailTitle(tab: AppTab): String = when (tab) {
    AppTab.CHATS -> stringResource(Res.string.chats_empty_state_select_conversation)
    AppTab.NODES -> stringResource(Res.string.contacts_list_select_node)
    AppTab.TOOLS -> stringResource(Res.string.tools_select_tool)
    AppTab.SETTINGS -> stringResource(Res.string.settings_select_setting)
    AppTab.MAP -> tabLabel(AppTab.MAP)
}

@Composable
internal fun emptyDetailSubtitle(tab: AppTab): String? = when (tab) {
    AppTab.CHATS, AppTab.NODES, AppTab.TOOLS, AppTab.SETTINGS -> tabLabel(tab)
    AppTab.MAP -> null
}

/** iOS split-column ContentUnavailableView glyphs. */
internal fun emptyDetailIcon(tab: AppTab): ImageVector? = when (tab) {
    AppTab.CHATS -> AppIcons.Chat
    AppTab.NODES -> AppIcons.Smartphone
    AppTab.TOOLS -> AppIcons.ToolsSelected
    AppTab.SETTINGS -> AppIcons.SettingsSelected
    AppTab.MAP -> null
}
