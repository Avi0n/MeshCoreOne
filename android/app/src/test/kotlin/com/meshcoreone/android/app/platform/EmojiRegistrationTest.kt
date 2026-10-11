// AndroidOnly: WP-407 Full source dataset/search and real Codable-compatible DataStore MRU/recovery; not a device result.
package com.meshcoreone.android.app.platform

import android.app.Application
import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.AppEmojiCatalog
import com.meshcoreone.android.app.container.AppEmojiPreferences
import com.meshcoreone.android.app.container.EmojiPreferenceSave
import com.meshcoreone.android.core.datastore.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.composer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class EmojiRegistrationTest {
    @Test fun fullCatalogSearchAndTwentyEntryMruUseTheActualPackagedDataAndStore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            storage.preferences.remove(AppStorageKey.frequentEmojis)
            val frequent = AppEmojiPreferences(storage.preferences, process)
            val catalog = AppEmojiCatalog(context.assets)
            val data = catalog.load()
            assertEquals(1932, data.values.sumOf { it.size })
            assertEquals(8, data.size)
            val holder = EmojiPickerStateHolder(EmojiCatalogSource { frequent.refresh(); data }, frequent, this)
            holder.load().join()
            assertEquals(EmojiPickerLoad.Loaded, holder.state.value.load)
            assertEquals(EmojiPickerStateHolder.CATEGORY_ORDER, holder.state.value.categories.map { it.id })
            val unique = data.values.flatten().map { it.unicode }.distinct().take(22)
            unique.forEach(holder::markAsFrequentlyUsed)
            holder.markAsFrequentlyUsed(unique[3])
            withTimeout(30_000) { frequent.save.first { it == EmojiPreferenceSave.Saved } }
            val persisted = AppEmojiPreferences.decode(storage.preferences.get(AppStorageKey.frequentEmojis))
            assertEquals(20, persisted.size)
            assertEquals(unique[3], persisted.first())
            assertEquals(20, persisted.distinct().size)
            assertEquals(frequent.read(), persisted)
            val reopened = AppEmojiPreferences(storage.preferences, process)
            reopened.refresh()
            assertEquals(persisted, reopened.read())
            holder.load().join()
            assertEquals("frequent", holder.state.value.categories.first().id)
            holder.setQuery("grinning").join()
            assertTrue(holder.state.value.categories.flatMap { it.emojis }.any { it.unicode == "😀" })
            val tagged = data.values.flatten().first { it.tags.isNotEmpty() }
            holder.setQuery(tagged.tags.first()).join()
            assertTrue(holder.state.value.categories.flatMap { it.emojis }.any { it.unicode == tagged.unicode })
            val coded = data.values.flatten().first { it.shortcodes.isNotEmpty() }
            holder.setQuery(coded.shortcodes.first()).join()
            assertTrue(holder.state.value.categories.flatMap { it.emojis }.any { it.unicode == coded.unicode })
            assertEquals(listOf("👍", "👨‍👩‍👧‍👦"), AppEmojiPreferences.decode(Bytes.utf8("[\"\\ud83d\\udc4d\",\"👨‍👩‍👧‍👦\"]")))
            try { AppEmojiPreferences.decode(Bytes.utf8("[42]")); fail("Typed JSON must reject non-strings") }
            catch (_: IllegalArgumentException) {}
        } finally { process.cancel(); storage.close() }
    }

    @Test fun lockedStorageRetainsThePendingSelectionAndAnExplicitRetryPersistsIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        val storage = MeshCoreStorage.get(context)
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            storage.preferences.set(AppStorageKey.frequentEmojis, AppEmojiPreferences.encode(listOf("👍")))
            val frequent = AppEmojiPreferences(storage.preferences, process)
            frequent.refresh()
            users.setUserUnlocked(false)
            frequent.write(listOf("❤️", "👍"))
            withTimeout(30_000) { frequent.save.first { it is EmojiPreferenceSave.Failed } }
            assertEquals(listOf("❤️", "👍"), frequent.read())
            users.setUserUnlocked(true)
            assertEquals(listOf("👍"), AppEmojiPreferences.decode(storage.preferences.get(AppStorageKey.frequentEmojis)))
            frequent.refresh()
            assertEquals(listOf("❤️", "👍"), frequent.read())
            assertTrue(frequent.save.value is EmojiPreferenceSave.Failed)
            frequent.retry()
            withTimeout(30_000) { frequent.save.first { it == EmojiPreferenceSave.Saved } }
            assertEquals(listOf("❤️", "👍"), AppEmojiPreferences.decode(storage.preferences.get(AppStorageKey.frequentEmojis)))
        } finally { users.setUserUnlocked(true); process.cancel(); storage.close() }
    }
}
