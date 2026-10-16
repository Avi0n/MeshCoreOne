package one.meshcore.shared.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import one.meshcore.shared.layout.SidebarLayout
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.setSidebarWide

/**
 * The adaptive root shell shared by the Android and desktop apps. It measures its own width at the
 * shell (the iOS analog: a column's size class is not trusted) and branches:
 * - below 600 dp: phone [CompactShell] with a bottom [NavigationBar]
 * - 600–779 dp: [CompactShell] with a [NavigationRail] and compact push navigation
 * - 780 dp and up: [WideShell] three-column list-detail
 *
 * The measured wide/narrow bit is written back into [coordinator] so sidebar-visibility derivation and
 * tap-collapses-in-one-write read the same tiling decision the shell branched on.
 */
@Composable
fun AppShell(
    coordinator: NavigationCoordinator,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    nodesContent: (@Composable () -> Unit)? = null,
    chatsContent: (@Composable () -> Unit)? = null,
    chatDetailContent: (@Composable (ChatRoute) -> Unit)? = null,
    contactDetailContent: (@Composable (contactId: String, fromDirectChat: Boolean) -> Unit)? = null,
    toolsContent: (@Composable () -> Unit)? = null,
    settingsContent: (@Composable () -> Unit)? = null,
    mapContent: (@Composable () -> Unit)? = null,
) {
    MaterialTheme {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints {
                val widthDp = maxWidth.value
                val isWide = SidebarLayout.isSidebarWide(widthDp)
                val useNavigationRail = SidebarLayout.isMaterialMedium(widthDp)
                LaunchedEffect(isWide) { coordinator.setSidebarWide(isWide) }
                if (isWide) {
                    WideShell(
                        coordinator = coordinator,
                        unreadCount = unreadCount,
                        nodesContent = nodesContent,
                        chatsContent = chatsContent,
                        chatDetailContent = chatDetailContent,
                        contactDetailContent = contactDetailContent,
                        toolsContent = toolsContent,
                        settingsContent = settingsContent,
                        mapContent = mapContent,
                    )
                } else {
                    CompactShell(
                        coordinator = coordinator,
                        unreadCount = unreadCount,
                        useNavigationRail = useNavigationRail,
                        nodesContent = nodesContent,
                        chatsContent = chatsContent,
                        chatDetailContent = chatDetailContent,
                        contactDetailContent = contactDetailContent,
                        toolsContent = toolsContent,
                        settingsContent = settingsContent,
                        mapContent = mapContent,
                    )
                }
            }
        }
    }
}
