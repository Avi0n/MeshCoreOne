package one.meshcore.shared.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.layout.SidebarLayout
import one.meshcore.shared.layout.SidebarVisibility
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NavKey
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.navigationIdentity
import one.meshcore.shared.navigation.popToRoot
import one.meshcore.shared.navigation.selectTab
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_empty_state_select_conversation
import one.meshcore.shared.resources.contacts_list_select_node
import org.jetbrains.compose.resources.stringResource

/**
 * The tablet/desktop shell: a pinned [NavigationRail] beside a list-detail area. Whether the section
 * shows its content-list column follows [NavigationCoordinator.sidebarVisibility] — tiled only when the
 * container is wide and no sidebar-collapsing tool is open — so Map and open Line of Sight / Trace Path
 * reclaim the content column. The rail stays visible so tab switching is always reachable.
 *
 * Tools and Settings render as true list-detail via [LocalShellContentRole]; collapsing tools fill the
 * remaining pane as detail (with list hidden).
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun WideShell(
    coordinator: NavigationCoordinator,
    unreadCount: Int,
    modifier: Modifier = Modifier,
    nodesContent: (@Composable () -> Unit)? = null,
    chatsContent: (@Composable () -> Unit)? = null,
    chatDetailContent: (@Composable (ChatRoute) -> Unit)? = null,
    contactDetailContent: (@Composable (contactId: String, fromDirectChat: Boolean) -> Unit)? = null,
    toolsContent: (@Composable () -> Unit)? = null,
    settingsContent: (@Composable () -> Unit)? = null,
    mapContent: (@Composable () -> Unit)? = null,
) {
    val selectedTab by coordinator.selectedTab.collectAsStateWithLifecycle()
    val visibility by coordinator.sidebarVisibility.collectAsStateWithLifecycle()
    val chatsRoute by coordinator.chatsSelectedRoute.collectAsStateWithLifecycle()
    val backStacks by coordinator.backStacks.collectAsStateWithLifecycle()
    val selectedContactId by coordinator.selectedContactId.collectAsStateWithLifecycle()
    val showingDiscovery by coordinator.nodesShowingDiscovery.collectAsStateWithLifecycle()
    val selectedTool by coordinator.selectedTool.collectAsStateWithLifecycle()
    val selectedSetting by coordinator.selectedSetting.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()

    val currentTab = AppTab.fromIndex(selectedTab)
    val showContentColumn = currentTab != AppTab.MAP && visibility == SidebarVisibility.Tiled

    Row(modifier = modifier.fillMaxSize()) {
        AppNavigationRail(
            selectedTab = currentTab,
            unreadCount = unreadCount,
            onSelectTab = { tab ->
                if (tab == currentTab) {
                    coordinator.popToRoot(tab)
                } else {
                    coordinator.selectTab(tab)
                }
            },
        )
        VerticalDivider()

        if (showContentColumn) {
            Surface(
                modifier = Modifier
                    .width(SidebarLayout.CONTENT_COLUMN_TILEABLE_MIN_WIDTH_DP.dp)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                CompositionLocalProvider(LocalShellContentRole provides ShellContentRole.WideList) {
                    when {
                        currentTab == AppTab.NODES && nodesContent != null -> nodesContent()
                        currentTab == AppTab.CHATS && chatsContent != null -> chatsContent()
                        currentTab == AppTab.TOOLS && toolsContent != null -> toolsContent()
                        currentTab == AppTab.SETTINGS && settingsContent != null -> settingsContent()
                        else -> PlaceholderPane(title = tabLabel(currentTab))
                    }
                }
            }
            VerticalDivider()
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            val chatsContactInfo = backStacks[AppTab.CHATS]?.lastOrNull() as? NavKey.ContactDetail
            val chatsContactInfoId = chatsContactInfo?.takeIf { it.fromDirectChat }?.contactId
            val detailKey = WideDetailKey(
                tab = currentTab,
                chatsRoute = chatsRoute,
                chatsContactInfoId = chatsContactInfoId,
                selectedContactId = selectedContactId,
                showingDiscovery = showingDiscovery,
                tool = selectedTool,
                setting = selectedSetting,
                contentCollapsed = !showContentColumn,
            )
            AnimatedContent(
                targetState = detailKey,
                contentKey = { it.copy(chatsRoute = it.chatsRoute?.navigationIdentity()) },
                transitionSpec = {
                    val forward = targetState.isDeeperThan(initialState)
                    hierarchicalTransform(forward = forward, reduceMotion = reduceMotion)
                },
                label = "wideShellDetail",
            ) { key ->
                DetailPane(
                    key = key,
                    chatDetailContent = chatDetailContent,
                    contactDetailContent = contactDetailContent,
                    mapContent = mapContent,
                    toolsContent = toolsContent,
                    settingsContent = settingsContent,
                )
            }
        }
    }
}

/**
 * Detail-pane identity for animation and empty-state selection. [contentCollapsed] is true when the
 * list column is hidden (Map, or a sidebar-collapsing tool) so tools/settings fill this pane as stacked.
 */
private data class WideDetailKey(
    val tab: AppTab,
    val chatsRoute: ChatRoute?,
    val chatsContactInfoId: String?,
    val selectedContactId: String?,
    val showingDiscovery: Boolean,
    val tool: one.meshcore.shared.navigation.ToolSelection?,
    val setting: one.meshcore.shared.navigation.SettingsDestination?,
    val contentCollapsed: Boolean,
) {
    fun isDeeperThan(other: WideDetailKey): Boolean {
        if (tab != other.tab) return true
        return when (tab) {
            AppTab.CHATS -> when {
                chatsContactInfoId != null && other.chatsContactInfoId == null -> true
                else -> chatsRoute != null && other.chatsRoute == null
            }
            AppTab.NODES -> selectedContactId != null && other.selectedContactId == null
            AppTab.TOOLS -> tool != null && other.tool == null
            AppTab.SETTINGS -> setting != null && other.setting == null
            AppTab.MAP -> false
        }
    }
}

@Composable
private fun DetailPane(
    key: WideDetailKey,
    chatDetailContent: (@Composable (ChatRoute) -> Unit)?,
    contactDetailContent: (@Composable (contactId: String, fromDirectChat: Boolean) -> Unit)?,
    mapContent: (@Composable () -> Unit)?,
    toolsContent: (@Composable () -> Unit)?,
    settingsContent: (@Composable () -> Unit)?,
) {
    when (key.tab) {
        AppTab.MAP -> mapContent?.invoke() ?: EmptyDetailPane(key)
        AppTab.CHATS -> if (key.chatsContactInfoId != null) {
            ChatContactInfoSlot(
                contactId = key.chatsContactInfoId,
                contactDetailContent = contactDetailContent,
            )
        } else {
            ChatDetailSlot(key, chatDetailContent)
        }
        AppTab.NODES -> ContactDetailSlot(key, contactDetailContent)
        AppTab.TOOLS -> RoleScopedContent(toolsContent) { EmptyDetailPane(key) }
        AppTab.SETTINGS -> RoleScopedContent(settingsContent) { EmptyDetailPane(key) }
    }
}

@Composable
private fun ChatDetailSlot(key: WideDetailKey, chatDetailContent: (@Composable (ChatRoute) -> Unit)?) {
    val route = key.chatsRoute
    if (route == null) {
        EmptyDetailPane(key)
        return
    }
    if (chatDetailContent != null) {
        chatDetailContent(route)
    } else {
        PlaceholderPane(
            title = route.conversationId,
            subtitle = stringResource(Res.string.chats_empty_state_select_conversation),
        )
    }
}

@Composable
private fun ChatContactInfoSlot(
    contactId: String,
    contactDetailContent: (@Composable (contactId: String, fromDirectChat: Boolean) -> Unit)?,
) {
    if (contactDetailContent != null) {
        contactDetailContent(contactId, true)
    } else {
        PlaceholderPane(title = contactId, subtitle = stringResource(Res.string.contacts_list_select_node))
    }
}

@Composable
private fun ContactDetailSlot(
    key: WideDetailKey,
    contactDetailContent: (@Composable (contactId: String, fromDirectChat: Boolean) -> Unit)?,
) {
    val contactId = key.selectedContactId
    if (contactId == null || key.showingDiscovery) {
        EmptyDetailPane(key)
        return
    }
    if (contactDetailContent != null) {
        contactDetailContent(contactId, false)
    } else {
        PlaceholderPane(title = contactId, subtitle = stringResource(Res.string.contacts_list_select_node))
    }
}

@Composable
private fun RoleScopedContent(content: (@Composable () -> Unit)?, empty: @Composable () -> Unit) {
    if (content == null) {
        empty()
        return
    }
    // A collapsed LOS/Trace list still leaves this pane beside the rail as the wide detail column.
    CompositionLocalProvider(LocalShellContentRole provides ShellContentRole.WideDetail) {
        content()
    }
}

@Composable
private fun EmptyDetailPane(key: WideDetailKey) {
    PlaceholderPane(
        title = emptyDetailTitle(key.tab),
        subtitle = emptyDetailSubtitle(key.tab),
        icon = emptyDetailIcon(key.tab),
    )
}
