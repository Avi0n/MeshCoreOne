package one.meshcore.shared.pathedit

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.decodeHex
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Per-radio LRU of recently added hop public keys, persisted through [PreferencesStore] — the Kotlin
 * analogue of the iOS `RecentHopsStore` (`UserDefaults`-backed). Most-recent first, capped at [LIMIT].
 * Shared by the contact path editor and the trace path builder so both present the same "Recent" section.
 *
 * The owning view model holds the observable list; this type owns only load/persist and the LRU transform.
 * The storage-key prefix is frozen verbatim from iOS. iOS persists a `UserDefaults` string array; the
 * KMP [PreferencesStore] has only scalar accessors, so the list is joined with [SEPARATOR] into one
 * string of lowercase hex keys. Recents aren't backed up and Android installs carry no legacy recents,
 * so the encoding need only round-trip within this store.
 */
class RecentHopsStore(private val preferences: PreferencesStore) {
    /** Load the persisted recents for [radioID], newest first; malformed entries are dropped. */
    suspend fun load(radioID: Uuid): List<ByteArray> {
        val stored = preferences.getString(storageKey(radioID)) ?: return emptyList()
        return stored.split(SEPARATOR)
            .filter { it.isNotEmpty() }
            .mapNotNull { decodeHex(it) }
    }

    /**
     * LRU-insert [pubkey] into [current], persist for [radioID], and return the new list. Moves an
     * existing key to the front rather than duplicating; trims to [LIMIT]. Stores lowercase hex, which
     * [decodeHex] round-trips case-insensitively.
     */
    suspend fun record(pubkey: ByteArray, current: List<ByteArray>, radioID: Uuid): List<ByteArray> {
        val updated = (listOf(pubkey) + current.filterNot { it.contentEquals(pubkey) }).take(LIMIT)
        preferences.putString(
            storageKey(radioID),
            updated.joinToString(SEPARATOR) { it.toLowercaseHex() },
        )
        return updated
    }

    companion object {
        const val LIMIT = 8

        /** Frozen storage-key prefix, verbatim from the iOS `RecentHopsStore`. */
        const val KEY_PREFIX = "pathEdit.recentPublicKeys."

        private const val SEPARATOR = ","

        fun storageKey(radioID: Uuid): String = "$KEY_PREFIX${radioID.value}"
    }
}
