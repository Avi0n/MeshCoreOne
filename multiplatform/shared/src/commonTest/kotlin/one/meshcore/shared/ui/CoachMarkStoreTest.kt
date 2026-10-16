package one.meshcore.shared.ui

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoachMarkStoreTest {
    @Test
    fun marksSeenAndResets() = runBlocking {
        val store = CoachMarkStore(InMemoryPreferencesStore())
        assertFalse(store.hasSeen(CoachMarkStore.DEVICE_MENU))
        store.markSeen(CoachMarkStore.DEVICE_MENU)
        assertTrue(store.hasSeen(CoachMarkStore.DEVICE_MENU))
        store.reset(CoachMarkStore.DEVICE_MENU)
        assertFalse(store.hasSeen(CoachMarkStore.DEVICE_MENU))
    }
}
