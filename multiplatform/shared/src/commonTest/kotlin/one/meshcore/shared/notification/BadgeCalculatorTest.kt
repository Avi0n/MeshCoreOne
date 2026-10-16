package one.meshcore.shared.notification

import kotlin.test.Test
import kotlin.test.assertEquals

/** The preference-aware badge sum (W2-spec §13.5), mirroring Swift `performBadgeUpdate`. */
class BadgeCalculatorTest {
    private val counts = UnreadCounts(contacts = 3, channels = 5, rooms = 7)

    @Test
    fun `sums every enabled category`() {
        assertEquals(15, BadgeCalculator.compute(counts, NotificationPreferencesSnapshot.AllEnabled))
    }

    @Test
    fun `badge disabled yields zero`() {
        val prefs = NotificationPreferencesSnapshot.AllEnabled.copy(badgeEnabled = false)
        assertEquals(0, BadgeCalculator.compute(counts, prefs))
    }

    @Test
    fun `a disabled category drops from the total`() {
        val prefs = NotificationPreferencesSnapshot.AllEnabled.copy(contactMessagesEnabled = false)
        assertEquals(12, BadgeCalculator.compute(counts, prefs))
    }

    @Test
    fun `only rooms when only rooms enabled`() {
        val prefs = NotificationPreferencesSnapshot.AllEnabled.copy(
            contactMessagesEnabled = false,
            channelMessagesEnabled = false,
            roomMessagesEnabled = true,
        )
        assertEquals(7, BadgeCalculator.compute(counts, prefs))
    }
}
