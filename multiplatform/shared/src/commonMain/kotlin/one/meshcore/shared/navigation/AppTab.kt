package one.meshcore.shared.navigation

import one.meshcore.shared.layout.SidebarVisibility

/**
 * The five main tabs. [index] is the selection integer each tab uses and MUST be preserved: the
 * navigation coordinator keeps the selected tab as an `Int`, and deep links and notification taps write
 * these raw integers, so a reorder would silently retarget saved and incoming navigation.
 *
 * Labels and icons live in the UI layer; this enum carries only identity and the two pure layout facts
 * the shell needs.
 */
enum class AppTab(val index: Int) {
    CHATS(0),
    NODES(1),
    MAP(2),
    TOOLS(3),
    SETTINGS(4),
    ;

    /**
     * The column visibility that hides this section's sidebar. Map is a two-column split, hidden with
     * [SidebarVisibility.DetailOnly]; the list sections are three-column, hidden with
     * [SidebarVisibility.ContentDetail] (sidebar gone, content + detail kept). One shared value cannot
     * collapse both shapes, so it is chosen per section.
     */
    val collapsedSidebarVisibility: SidebarVisibility
        get() = if (this == MAP) SidebarVisibility.DetailOnly else SidebarVisibility.ContentDetail

    /**
     * Whether this tab hosts the BLE status indicator (and is therefore a valid tab to donate the
     * device-menu tip on). Matches the iOS `isOnValidTabForDeviceMenuTip`: Chats, Nodes, and Map.
     */
    val showsConnectionIndicator: Boolean
        get() = this == CHATS || this == NODES || this == MAP

    companion object {
        /** The tab for [index], or [CHATS] as the iOS shell's fallback for an unknown index. */
        fun fromIndex(index: Int): AppTab = entries.firstOrNull { it.index == index } ?: CHATS
    }
}
