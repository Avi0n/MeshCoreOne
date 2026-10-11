// AndroidOnly: WP-407 Source-selected bundled Emojibase catalog and process-owned durable frequent-emoji writes.
package com.meshcoreone.android.app.container

import android.content.res.AssetManager
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.composer.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

internal class AppEmojiCatalog(private val assets: AssetManager) : EmojiCatalogSource {
    override suspend fun load(): Map<String, List<EmojiEntry>> = withContext(Dispatchers.IO) {
        val bytes = assets.open("emoji/emojibase.json").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == "b69315f939d4a2c4332e09b70c8278e0a9e0e7465d98c71f95e50b7ad76a4cfb") {
            "Bundled emoji catalog differs from the source-selected resource"
        }
        val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
        check(json.keys().asSequence().toSet() == EmojiPickerStateHolder.CATEGORY_ORDER.toSet())
        EmojiPickerStateHolder.CATEGORY_ORDER.associateWith { category ->
            val entries = json.getJSONArray(category)
            List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                EmojiEntry(entry.getString("hexcode"), entry.getString("unicode"), entry.getString("label"),
                    entry.getJSONArray("shortcodes").strings(), entry.optJSONArray("tags")?.strings().orEmpty())
            }
        }.also { check(it.values.sumOf(List<EmojiEntry>::size) == 1932) }
    }
}

internal sealed interface EmojiPreferenceSave {
    data object Saved : EmojiPreferenceSave
    data object Saving : EmojiPreferenceSave
    data class Failed(val cause: Exception) : EmojiPreferenceSave
}

internal class AppEmojiPreferences(private val store: PreferenceStore, scope: CoroutineScope) : FrequentEmojiStore {
    private data class Request(val sequence: Long, val emojis: List<String>)
    private val sequence = AtomicLong()
    private val lock = Any()
    private val requests = MutableStateFlow<Request?>(null)
    @Volatile private var saved = emptyList<String>()
    private val mutableSave = MutableStateFlow<EmojiPreferenceSave>(EmojiPreferenceSave.Saved)
    val save: StateFlow<EmojiPreferenceSave> = mutableSave.asStateFlow()

    init {
        scope.launch {
            requests.filterNotNull().collect { request ->
                try {
                    val expected = encode(request.emojis)
                    val persisted = store.update { this[AppStorageKey.frequentEmojis] = expected }
                    check(persisted[AppStorageKey.frequentEmojis] == expected) { "Frequent emojis were not persisted" }
                    saved = decode(persisted[AppStorageKey.frequentEmojis])
                    synchronized(lock) {
                        if (requests.compareAndSet(request, null)) mutableSave.value = EmojiPreferenceSave.Saved
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    Logger.getLogger("com.mc1.EmojiPreferences").log(Level.WARNING, "Frequent emoji persistence failed", failure)
                    synchronized(lock) {
                        if (requests.value == request) mutableSave.value = EmojiPreferenceSave.Failed(failure)
                    }
                }
            }
        }
    }

    suspend fun refresh() {
        val revision = synchronized(lock) {
            if (requests.value != null) return
            sequence.get()
        }
        val persisted = decode(store.get(AppStorageKey.frequentEmojis))
        synchronized(lock) {
            if (requests.value == null && sequence.get() == revision) saved = persisted
        }
    }

    override fun read(): List<String> = requests.value?.emojis ?: saved

    // The synchronous feature seam accepts a desired state, not a fabricated disk-write completion.
    // Each desired list includes earlier pending selections; only the latest complete state may conflate.
    override fun write(emojis: List<String>) {
        require(emojis.size <= EmojiPickerStateHolder.MAX_FREQUENT)
        synchronized(lock) {
            mutableSave.value = EmojiPreferenceSave.Saving
            requests.value = Request(sequence.incrementAndGet(), emojis.toList())
        }
    }

    fun retry() { requests.value?.let { write(it.emojis) } }

    companion object {
        internal fun encode(emojis: List<String>): Bytes = Bytes.utf8(JSONArray(emojis).toString())
        internal fun decode(bytes: Bytes): List<String> =
            if (bytes.isEmpty) emptyList() else JSONArray(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)).strings()
    }
}

private fun JSONArray.strings(): List<String> = List(length()) { index ->
    get(index) as? String ?: throw IllegalArgumentException("Emoji JSON array must contain only strings")
}
