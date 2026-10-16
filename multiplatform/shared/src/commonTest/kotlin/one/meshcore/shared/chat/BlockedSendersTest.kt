package one.meshcore.shared.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors the iOS `SyncCoordinatorMessageHandlerTests` blocked-sender-cache family. */
class BlockedSendersTest {
    @Test
    fun emptyCacheBlocksNoOne() = runTest {
        val cache = BlockedSendersCache()
        assertFalse(cache.isBlocked("SomeNode"))
    }

    @Test
    fun refreshLoadsBlockedNames() = runTest {
        val cache = BlockedSendersCache()
        cache.refresh(listOf("BlockedPerson"))
        assertTrue(cache.isBlocked("BlockedPerson"))
    }

    @Test
    fun nullNameIsNeverBlocked() = runTest {
        val cache = BlockedSendersCache()
        cache.refresh(listOf("BlockedPerson"))
        assertFalse(cache.isBlocked(null))
    }

    @Test
    fun refreshReplacesPreviousCache() = runTest {
        val cache = BlockedSendersCache()
        cache.refresh(listOf("WasBlocked"))
        assertTrue(cache.isBlocked("WasBlocked"))

        cache.refresh(emptyList())
        assertFalse(cache.isBlocked("WasBlocked"))
    }

    @Test
    fun snapshotReturnsCachedNames() = runTest {
        val cache = BlockedSendersCache()
        cache.refresh(listOf("Blocked1", "Blocked2"))
        val names = cache.snapshot()
        assertTrue(names.contains("Blocked1"))
        assertTrue(names.contains("Blocked2"))
    }

    @Test
    fun matchingIsCaseSensitive() = runTest {
        val cache = BlockedSendersCache()
        cache.refresh(listOf("BlockedPerson"))
        assertFalse(cache.isBlocked("blockedperson"))
    }
}
