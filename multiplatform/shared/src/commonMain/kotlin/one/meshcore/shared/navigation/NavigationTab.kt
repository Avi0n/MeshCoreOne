package one.meshcore.shared.navigation

/** Selects [tab] from a bottom-bar or rail tap. The collapsed shape follows via [sidebarVisibility]. */
fun NavigationCoordinator.selectTab(tab: AppTab) {
    // Manual tab change abandons a pending map-return (user chose a different destination).
    if (tab != AppTab.MAP) {
        mapReturnTabIndex = null
    }
    selectedTabIndex.value = tab.index
    // Re-entering a tab that hosts an open chat keeps the bar hidden so the thread stays full-screen,
    // matching a fresh push (only the Chats tab ever carries a bar-hiding ChatDetail on top).
    tabBarVisibleState.value = backStackOf(tab).lastOrNull() !is NavKey.ChatDetail
}

/** Records the measured-width tiling decision from the shell. */
fun NavigationCoordinator.setSidebarWide(wide: Boolean) {
    sidebarWide.value = wide
}

/** Opens [tool] on the Tools tab; the sidebar collapses if the tool prefers it. */
fun NavigationCoordinator.selectTool(tool: ToolSelection?) {
    if (selectedToolState.value != tool) cliInitialKeyboardFocusClaimed = false
    selectedToolState.value = tool
}

/** Claims first-opening focus across remounts of the same selected CLI presentation. */
fun NavigationCoordinator.claimCliInitialKeyboardFocus(): Boolean {
    if (selectedToolState.value != ToolSelection.CLI || cliInitialKeyboardFocusClaimed) return false
    cliInitialKeyboardFocusClaimed = true
    return true
}

/** Opens [setting] on the Settings tab (or clears selection with null). */
fun NavigationCoordinator.selectSetting(setting: SettingsDestination?) {
    selectedSettingState.value = setting
}

/** Opens [setting] on the Settings tab from outside it (a launcher shortcut or deep link). */
fun NavigationCoordinator.navigateToSettings(setting: SettingsDestination) {
    tabBarVisibleState.value = true
    selectedSettingState.value = setting
    selectedTabIndex.value = AppTab.SETTINGS.index
}

/**
 * Whether system / predictive back should be consumed instead of finishing the activity.
 * Covers per-tab stacks, open tools, and open settings pages (tools/settings are not NavKeys).
 */
fun NavigationCoordinator.canHandleSystemBack(): Boolean {
    val tab = AppTab.fromIndex(selectedTabIndex.value)
    val toolOpen = tab == AppTab.TOOLS && selectedToolState.value != null
    val settingOpen = tab == AppTab.SETTINGS && selectedSettingState.value != null
    return toolOpen || settingOpen || canPop(tab)
}

/**
 * Android system-back / predictive-back entry. Pops tool → nested setting parent → setting hub →
 * stack in that order for the selected tab. Returns true when the event was consumed; false means
 * the platform should finish (or otherwise handle) the activity.
 */
fun NavigationCoordinator.handleSystemBack(): Boolean {
    val tab = AppTab.fromIndex(selectedTabIndex.value)
    val clearedInTab = when {
        tab == AppTab.TOOLS && selectedToolState.value != null -> {
            selectTool(null)
            true
        }
        tab == AppTab.SETTINGS && selectedSettingState.value != null -> {
            selectedSettingState.value = selectedSettingState.value?.backParent
            true
        }
        else -> false
    }
    return clearedInTab || pop(tab)
}
