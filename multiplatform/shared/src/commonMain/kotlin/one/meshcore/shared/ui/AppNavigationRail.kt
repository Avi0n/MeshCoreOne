package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.layout.SidebarLayout
import one.meshcore.shared.navigation.AppTab

/**
 * The five-tab [NavigationRail] shared by the medium compact shell and the wide list-detail shell.
 * Always visible: Material keeps the rail during a chat push (the phone [NavigationBar] still hides).
 */
@Composable
internal fun AppNavigationRail(
    selectedTab: AppTab,
    unreadCount: Int,
    onSelectTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(SidebarLayout.SIDEBAR_COLUMN_WIDTH_DP.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Spacer(Modifier.height(RAIL_HEADER_SPACER_DP.dp))
            AppTab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = tab == selectedTab,
                    onClick = { onSelectTab(tab) },
                    icon = {
                        RailIcon(
                            tab = tab,
                            selected = tab == selectedTab,
                            unreadCount = unreadCount,
                        )
                    },
                    label = { Text(tabLabel(tab), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
private fun RailIcon(tab: AppTab, selected: Boolean, unreadCount: Int) {
    val vector = tabIcon(tab, selected)
    if (tab == AppTab.CHATS && unreadCount > 0) {
        BadgedBox(badge = {
            Badge {
                Text(
                    if (unreadCount >
                        BADGE_OVERFLOW_THRESHOLD
                    ) {
                        "99+"
                    } else {
                        unreadCount.toString()
                    },
                )
            }
        }) {
            Icon(imageVector = vector, contentDescription = null)
        }
    } else {
        Icon(imageVector = vector, contentDescription = null)
    }
}

/** Material rail top inset so the first item is not flush with the status/app chrome. */
private const val RAIL_HEADER_SPACER_DP = 8
private const val BADGE_OVERFLOW_THRESHOLD = 99
