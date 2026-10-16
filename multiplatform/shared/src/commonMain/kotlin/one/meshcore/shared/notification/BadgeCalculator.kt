package one.meshcore.shared.notification

/** Per-category unread totals the app-icon badge sums (the Swift `getBadgeCount` tuple). */
data class UnreadCounts(val contacts: Int, val channels: Int, val rooms: Int) {
    companion object {
        val Zero = UnreadCounts(contacts = 0, channels = 0, rooms = 0)
    }
}

/**
 * The app-icon badge total. Mirrors Swift `performBadgeUpdate`: the badge toggle off means zero, and each
 * category is summed only when its own toggle is enabled, so muting a category drops its unreads from the
 * count.
 */
object BadgeCalculator {
    fun compute(counts: UnreadCounts, prefs: NotificationPreferencesSnapshot): Int {
        if (!prefs.badgeEnabled) return 0
        var total = 0
        if (prefs.contactMessagesEnabled) total += counts.contacts
        if (prefs.channelMessagesEnabled) total += counts.channels
        if (prefs.roomMessagesEnabled) total += counts.rooms
        return total
    }
}
