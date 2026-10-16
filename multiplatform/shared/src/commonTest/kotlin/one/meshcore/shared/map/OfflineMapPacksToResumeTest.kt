package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineMapPacksToResumeTest {
    @Test
    fun resumesIncompletePacksTheUserDidNotPause() {
        val active = pack(id = "a", completed = false, paused = false)
        val userPaused = pack(id = "b", completed = false, paused = true)
        val done = pack(id = "c", completed = true, paused = false)
        assertEquals(listOf(active), offlineMapPacksToResume(listOf(active, userPaused, done)))
    }

    @Test
    fun emptyWhenEveryPackIsDoneOrPaused() {
        assertEquals(
            emptyList(),
            offlineMapPacksToResume(
                listOf(
                    pack(id = "done", completed = true, paused = false),
                    pack(id = "paused", completed = false, paused = true),
                ),
            ),
        )
    }

    private fun pack(id: String, completed: Boolean, paused: Boolean) = OfflineMapPack(
        id = id,
        name = id,
        layer = OfflineMapLayer.BASE,
        createdAtEpochSeconds = 1L,
        completed = completed,
        paused = paused,
    )
}
