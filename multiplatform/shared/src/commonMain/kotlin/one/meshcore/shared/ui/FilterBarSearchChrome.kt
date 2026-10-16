package one.meshcore.shared.ui

/** Filter chips hide while search is active instead of cloning iOS `GlassFilterBar` dimming. */
internal fun filterBarVisible(isSearching: Boolean): Boolean = !isSearching
