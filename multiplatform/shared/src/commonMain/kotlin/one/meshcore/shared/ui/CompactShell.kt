package one.meshcore.shared.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.chat.ui.currentImeBottomPx
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NavKey
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.navigationIdentity
import one.meshcore.shared.navigation.popToRoot
import one.meshcore.shared.navigation.selectTab

/**
 * The compact shell: five tabs with a push stack. Below Material medium this is a bottom
 * [NavigationBar] (hidden while a chat is open, matching iOS `tabBarVisibility`). From 600 dp it is a
 * pinned [NavigationRail] with the same push stack — not three-column tiling.
 *
 * Tab switches cross-fade; hierarchical push/pop use a short shared-axis slide (respecting reduce-motion).
 */
@Composable
internal fun CompactShell(
    coordinator: NavigationCoordinator,
    unreadCount: Int,
    useNavigationRail: Boolean,
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
    val tabBarVisible by coordinator.tabBarVisible.collectAsStateWithLifecycle()
    val backStacks by coordinator.backStacks.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    val imeVisible = currentImeBottomPx() > 0

    val currentTab = AppTab.fromIndex(selectedTab)
    val topKey = backStacks[currentTab]?.lastOrNull()
    val bodyKey = CompactBodyKey(currentTab, topKey)
    val onSelectTab: (AppTab) -> Unit = { tab ->
        if (tab == currentTab) coordinator.popToRoot(tab) else coordinator.selectTab(tab)
    }

    @Composable
    fun Body(bodyModifier: Modifier) {
        CompactShellBody(
            currentTab = currentTab,
            topKey = topKey,
            bodyKey = bodyKey,
            reduceMotion = reduceMotion,
            nodesContent = nodesContent,
            chatsContent = chatsContent,
            chatDetailContent = chatDetailContent,
            contactDetailContent = contactDetailContent,
            toolsContent = toolsContent,
            settingsContent = settingsContent,
            mapContent = mapContent,
            modifier = bodyModifier,
        )
    }

    if (useNavigationRail) {
        Row(modifier.fillMaxSize()) {
            AppNavigationRail(
                selectedTab = currentTab,
                unreadCount = unreadCount,
                onSelectTab = onSelectTab,
            )
            VerticalDivider()
            Body(Modifier.weight(1f).fillMaxSize())
        }
    } else {
        // The shell adds no window insets of its own: every tab root and pushed screen hosts its own
        // Scaffold/TopAppBar that draws under the status bar and consumes the real insets. Consuming the
        // bottom NavigationBar height keeps those inner Scaffolds from re-adding the navigation-bar inset.
        Scaffold(
            modifier = modifier,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (shouldShowCompactTabBar(tabBarVisible, imeVisible)) {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == currentTab,
                                onClick = { onSelectTab(tab) },
                                icon = {
                                    TabBarIcon(
                                        tab = tab,
                                        selected = tab == currentTab,
                                        unreadCount = unreadCount,
                                    )
                                },
                                label = { Text(tabLabel(tab)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Body(Modifier.padding(innerPadding).consumeWindowInsets(innerPadding))
        }
    }
}

internal fun shouldShowCompactTabBar(tabBarVisible: Boolean, imeVisible: Boolean): Boolean =
    tabBarVisible && !imeVisible

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun CompactShellBody(
    currentTab: AppTab,
    topKey: NavKey?,
    bodyKey: CompactBodyKey,
    reduceMotion: Boolean,
    nodesContent: (@Composable () -> Unit)?,
    chatsContent: (@Composable () -> Unit)?,
    chatDetailContent: (@Composable (ChatRoute) -> Unit)?,
    contactDetailContent: (@Composable (contactId: String, fromDirectChat: Boolean) -> Unit)?,
    toolsContent: (@Composable () -> Unit)?,
    settingsContent: (@Composable () -> Unit)?,
    mapContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val showingChatThread = currentTab == AppTab.CHATS && topKey is NavKey.ChatDetail
    Box(modifier) {
        // Host the conversation list outside AnimatedContent so a thread push does not
        // remount it. Filter/search then survive Back, matching iOS NavigationStack.
        if (currentTab == AppTab.CHATS && chatsContent != null) {
            Box(if (showingChatThread) Modifier.size(0.dp) else Modifier.fillMaxSize()) {
                chatsContent()
            }
        }
        AnimatedContent(
            targetState = bodyKey,
            contentKey = { key ->
                val chat = key.topKey as? NavKey.ChatDetail
                key.copy(topKey = chat?.copy(route = chat.route.navigationIdentity()) ?: key.topKey)
            },
            transitionSpec = {
                val tabChanged = initialState.tab != targetState.tab
                val forward = when {
                    tabChanged -> true
                    initialState.topKey == null && targetState.topKey != null -> true
                    initialState.topKey != null && targetState.topKey == null -> false
                    else -> true
                }
                if (tabChanged) {
                    tabSwitchTransform(reduceMotion)
                } else {
                    hierarchicalTransform(forward = forward, reduceMotion = reduceMotion)
                }
            },
            label = "compactShellBody",
            // The chats list is the sibling above, so this layer's chats root is only a stand-in.
            // That stand-in has to be full-bleed: a zero-size one is the rect AnimatedContent morphs
            // the next screen out of, which reads as a grow from the lower-right corner.
            modifier = Modifier.fillMaxSize(),
        ) { key ->
            CompactTabBody(
                currentTab = key.tab,
                topKey = key.topKey,
                nodesContent = nodesContent,
                chatsContent = if (key.tab == AppTab.CHATS) null else chatsContent,
                chatDetailContent = chatDetailContent,
                contactDetailContent = contactDetailContent,
                toolsContent = toolsContent,
                settingsContent = settingsContent,
                mapContent = mapContent,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Identity for [AnimatedContent]: tab + optional pushed destination. */
private data class CompactBodyKey(val tab: AppTab, val topKey: NavKey?)

@Composable
private fun CompactTabBody(
    currentTab: AppTab,
    topKey: NavKey?,
    nodesContent: (@Composable () -> Unit)?,
    chatsContent: (@Composable () -> Unit)?,
    chatDetailContent: (@Composable (ChatRoute) -> Unit)?,
    contactDetailContent: (@Composable (contactId: String, fromDirectChat: Boolean) -> Unit)?,
    toolsContent: (@Composable () -> Unit)?,
    settingsContent: (@Composable () -> Unit)?,
    mapContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val pushed = topKey
    when {
        pushed is NavKey.ChatDetail -> {
            if (chatDetailContent != null) {
                Box(modifier = modifier) { chatDetailContent(pushed.route) }
            } else {
                PlaceholderPane(title = navKeyLabel(pushed), subtitle = tabLabel(currentTab), modifier = modifier)
            }
        }
        pushed is NavKey.ContactDetail -> {
            if (contactDetailContent != null) {
                Box(modifier = modifier) { contactDetailContent(pushed.contactId, pushed.fromDirectChat) }
            } else {
                PlaceholderPane(title = navKeyLabel(pushed), subtitle = tabLabel(currentTab), modifier = modifier)
            }
        }
        // Discovery / Blocked / MapFocus are full-tab destinations rendered by the tab root content
        // (ContactListRoot observes coordinator flags; map keeps mapContent). They only exist on the
        // stack so system Back is consumed.
        pushed is NavKey.Discovery ||
            pushed is NavKey.BlockedContacts ||
            pushed is NavKey.MapFocus ||
            pushed == null -> CompactTabRoot(
            currentTab = currentTab,
            nodesContent = nodesContent,
            chatsContent = chatsContent,
            toolsContent = toolsContent,
            settingsContent = settingsContent,
            mapContent = mapContent,
            modifier = modifier,
        )
        else -> PlaceholderPane(
            title = navKeyLabel(pushed),
            subtitle = tabLabel(currentTab),
            modifier = modifier,
        )
    }
}

@Composable
private fun CompactTabRoot(
    currentTab: AppTab,
    nodesContent: (@Composable () -> Unit)?,
    chatsContent: (@Composable () -> Unit)?,
    toolsContent: (@Composable () -> Unit)?,
    settingsContent: (@Composable () -> Unit)?,
    mapContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    when {
        currentTab == AppTab.NODES && nodesContent != null -> Box(modifier = modifier) { nodesContent() }
        currentTab == AppTab.CHATS && chatsContent != null -> Box(modifier = modifier) { chatsContent() }
        currentTab == AppTab.CHATS -> Box(modifier.fillMaxSize())
        currentTab == AppTab.TOOLS && toolsContent != null -> Box(modifier = modifier) { toolsContent() }
        currentTab == AppTab.SETTINGS && settingsContent != null -> Box(modifier = modifier) { settingsContent() }
        currentTab == AppTab.MAP && mapContent != null -> Box(modifier = modifier) { mapContent() }
        else -> PlaceholderPane(title = tabLabel(currentTab), modifier = modifier)
    }
}

@Composable
private fun TabBarIcon(tab: AppTab, selected: Boolean, unreadCount: Int) {
    val label = tabLabel(tab)
    val vector = tabIcon(tab, selected)
    if (tab == AppTab.CHATS && unreadCount > 0) {
        BadgedBox(badge = { Badge { Text(badgeText(unreadCount)) } }) {
            Icon(imageVector = vector, contentDescription = label)
        }
    } else {
        Icon(imageVector = vector, contentDescription = label)
    }
}

private const val BADGE_OVERFLOW_THRESHOLD = 99

private fun badgeText(count: Int): String = if (count > BADGE_OVERFLOW_THRESHOLD) "99+" else count.toString()
