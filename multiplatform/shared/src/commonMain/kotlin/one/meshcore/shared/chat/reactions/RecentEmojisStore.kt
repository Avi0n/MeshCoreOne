package one.meshcore.shared.chat.reactions

import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Recently used reaction emojis for the quick-access row, ported from iOS `RecentEmojisStore`. Falls back
 * to [DEFAULT_EMOJIS] when nothing is stored. Most-recent-first; capped at [MAX_RECENT].
 */
class RecentEmojisStore(
    private val preferences: PreferencesStore? = null,
    private val maxRecent: Int = MAX_RECENT,
    private val prefsKey: String = PREFS_KEY,
    private val emptyFallback: List<String> = DEFAULT_EMOJIS,
) {
    /** Recently used emojis (most recent first), or [emptyFallback] when nothing is stored. */
    var recentEmojis: List<String> = emptyFallback
        private set

    /** Loads the stored list from preferences (no-op without a store); empty → [emptyFallback]. */
    suspend fun load() {
        val raw = preferences?.getString(prefsKey)
        recentEmojis = decode(raw, maxRecent, emptyFallback)
    }

    /** Records [emoji] usage, moving it to the front of the recent list and persisting when possible. */
    suspend fun recordUsage(emoji: String) {
        val next = (listOf(emoji) + recentEmojis.filterNot { it == emoji }).take(maxRecent)
        recentEmojis = next
        preferences?.putString(prefsKey, encode(next))
    }

    companion object {
        val DEFAULT_EMOJIS = listOf("👍", "👎", "❤️", "😂", "😮", "😢")

        /** Expanded set shown in the "more emojis" sheet until full category browsing lands. */
        val EXTENDED_EMOJIS = DEFAULT_EMOJIS + listOf(
            "🔥", "🎉", "🙏", "👏", "💯", "✅",
            "❌", "⚠️", "🆘", "📍", "🛰️", "📡",
            "😊", "😅", "🤔", "👀", "💪", "🫡",
        )

        const val MAX_RECENT = 6
        const val MAX_FREQUENT = 20
        const val PREFS_KEY = PersistenceKeys.RECENT_REACTION_EMOJIS
        const val FREQUENT_PREFS_KEY = PersistenceKeys.FREQUENT_EMOJIS

        private const val SEPARATOR = "\u001F"

        fun encode(emojis: List<String>): String = emojis.joinToString(SEPARATOR)

        fun decode(raw: String?, max: Int = MAX_RECENT, emptyFallback: List<String> = DEFAULT_EMOJIS): List<String> {
            if (raw.isNullOrEmpty()) return emptyFallback
            val parts = raw.split(SEPARATOR).filter { it.isNotEmpty() }
            return if (parts.isEmpty()) emptyFallback else parts.take(max)
        }
    }
}
