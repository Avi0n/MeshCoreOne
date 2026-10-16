package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.prefs.runPreferences

/**
 * Disk-backed store for per-conversation chat input drafts — the text a user typed into the composer
 * but did not send, so it survives leaving and re-entering a conversation and survives an app restart.
 * The Kotlin analogue of the iOS `DraftStore`.
 *
 * iOS holds the drafts in a single `UserDefaults` dictionary key; Kotlin's [PreferencesStore] seam has
 * no dictionary type, so the whole `[draftStorageKey: text]` map is serialized to JSON under one string
 * key ([STORAGE_KEY]) and rewritten on every change. An in-memory cache backs the reads, loaded once in
 * [create] or [loading], mirroring the iOS store's init-time snapshot; a corrupt or absent blob loads as empty. A
 * [Mutex] serializes the cache mutation and its persist so concurrent composers can't interleave a
 * half-written map.
 *
 * Drafts are device-local and intentionally excluded from the backup envelope: [STORAGE_KEY] must never
 * gain a backup mapping. Entries are removed when a draft is sent, when the composer is emptied, and when
 * a channel slot is vacated (channel delete, sync prune, backup-import relocation) so a channel later
 * reusing a freed slot can't surface the prior channel's draft.
 */
class DraftStore private constructor(
    private val preferences: PreferencesStore,
    private val warning: (String) -> Unit,
    initial: Map<String, String>,
    private val initialLoad: Deferred<Map<String, String>>? = null,
) {
    private val mutex = Mutex()
    private val cache: MutableMap<String, String> = initial.toMutableMap()
    private var didApplyInitialLoad = initialLoad == null

    /** The stored draft for a conversation, or `null` if none. */
    suspend fun draft(id: ChatConversationID): String? {
        ensureLoaded()
        return mutex.withLock { cache[id.draftStorageKey] }
    }

    /**
     * Stores [text] as the draft for [id], or removes the entry when [text] is empty or
     * whitespace/newline-only. Trimming is used only for the emptiness test; a non-empty draft (e.g.
     * with a trailing newline) is stored verbatim.
     */
    suspend fun setDraft(text: String, id: ChatConversationID) {
        ensureLoaded()
        mutex.withLock {
            val key = id.draftStorageKey
            if (text.trim().isEmpty()) {
                if (cache.remove(key) != null) persist()
            } else {
                cache[key] = text
                persist()
            }
        }
    }

    /** Removes the draft for [id], if present. */
    suspend fun clearDraft(id: ChatConversationID) {
        ensureLoaded()
        mutex.withLock {
            if (cache.remove(id.draftStorageKey) != null) persist()
        }
    }

    /**
     * Removes the drafts for the given channel slots on [radioID], persisting once for the whole batch.
     * Used by the single-radio slot-vacate paths (channel delete, sync prune).
     */
    suspend fun clearChannelDrafts(radioID: Uuid, indices: Set<Int>) = clearChannelDrafts(mapOf(radioID to indices))

    /**
     * Removes the drafts for the given channel slots grouped by radio, persisting once for the whole
     * batch. Used by the backup-import path, where affected slots arrive per radio.
     */
    suspend fun clearChannelDrafts(slotsByRadio: Map<Uuid, Set<Int>>) =
        clearChannelDrafts(slotsByRadio, persistUnchanged = false)

    internal suspend fun clearChannelDrafts(slotsByRadio: Map<Uuid, Set<Int>>, persistUnchanged: Boolean) {
        ensureLoaded()
        mutex.withLock {
            var didChange = false
            for ((radioID, indices) in slotsByRadio) {
                for (index in indices) {
                    val key = ChatConversationID.channel(radioID, index).draftStorageKey
                    if (cache.remove(key) != null) didChange = true
                }
            }
            if (didChange || persistUnchanged) persist()
        }
    }

    /**
     * Pure restore decision: returns the saved draft only when the composer is empty, otherwise `null`.
     * Keeps a reconnect-driven re-restore (or a double initial load) from clobbering text the user is
     * already typing.
     */
    suspend fun draftToApply(currentText: String, id: ChatConversationID): String? =
        if (currentText.isEmpty()) draft(id) else null

    private suspend fun ensureLoaded() {
        val loaded = initialLoad?.await() ?: return
        mutex.withLock {
            if (!didApplyInitialLoad) {
                cache.putAll(loaded)
                didApplyInitialLoad = true
            }
        }
    }

    private suspend fun persist() {
        // iOS UserDefaults.set does not throw. A DataStore fault keeps the in-memory draft.
        runPreferences(warning, DRAFT_PERSIST_FAILED, fallback = {}) {
            preferences.putString(STORAGE_KEY, json.encodeToString(mapSerializer, cache.toMap()))
        }
    }

    companion object {
        /** The single [PreferencesStore] key holding the JSON `[draftStorageKey: text]` map. */
        const val STORAGE_KEY = "chat.drafts.v1"

        private val json = Json { ignoreUnknownKeys = true }
        private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
        private const val DRAFT_PERSIST_FAILED = "Draft persist failed"

        /**
         * Builds a store, loading the persisted draft map once from [preferences]. A missing or
         * unparseable blob loads as empty rather than throwing, so a corrupt entry can't wedge the
         * composer. [warning] records a preferences fault; omitting it keeps the in-memory draft
         * and drops the log.
         */
        suspend fun create(preferences: PreferencesStore, warning: (String) -> Unit = {}): DraftStore =
            DraftStore(preferences, warning, loadInitial(preferences))

        /** Starts the one-time disk read without awaiting it during construction; draft operations await it. */
        fun loading(preferences: PreferencesStore, scope: CoroutineScope, warning: (String) -> Unit = {}): DraftStore =
            DraftStore(
                preferences = preferences,
                warning = warning,
                initial = emptyMap(),
                initialLoad = scope.async { loadInitial(preferences) },
            )

        private suspend fun loadInitial(preferences: PreferencesStore): Map<String, String> =
            preferences.getString(STORAGE_KEY)
                ?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() }
                ?: emptyMap()
    }
}
