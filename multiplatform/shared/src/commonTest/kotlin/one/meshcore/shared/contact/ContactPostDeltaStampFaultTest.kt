package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed post-delta lastHeard stamp is logged and later drained keys still stamp. Cancellation
 * aborts the drain loop. A thrown error stays on that logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactPostDeltaStampFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledPostDeltaStampAbortsTheDrainLoop() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val store = seededStore(stopped)
        val service = loggedContactService(store, logs)
        val faultKey = publicKey(FAULT_KEY_BYTE)
        val laterKey = publicKey(LATER_KEY_BYTE)

        val thrown = assertFailsWith<CancellationException> {
            service.stampAdvertReceiveTimes(drainedKeys(faultKey, laterKey), receiveTimes(faultKey, laterKey), radioID)
        }

        assertSame(stopped, thrown)
        assertEquals(PRIOR_HEARD, store.fetchContact(radioID, faultKey)?.lastHeardTimestamp)
        assertEquals(PRIOR_HEARD, store.fetchContact(radioID, laterKey)?.lastHeardTimestamp)
        assertTrue(logs.none { it.message.contains(POST_DELTA_STAMP_FAILED) })
    }

    @Test
    fun aFailedPostDeltaStampStillStampsLaterKeys() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val store = seededStore(PostDeltaStampFailure())
        val service = loggedContactService(store, logs)
        val faultKey = publicKey(FAULT_KEY_BYTE)
        val laterKey = publicKey(LATER_KEY_BYTE)

        service.stampAdvertReceiveTimes(drainedKeys(faultKey, laterKey), receiveTimes(faultKey, laterKey), radioID)

        assertEquals(PRIOR_HEARD, store.fetchContact(radioID, faultKey)?.lastHeardTimestamp)
        assertEquals(RECEIVED_AT, store.fetchContact(radioID, laterKey)?.lastHeardTimestamp)
        val line = logs.single { it.message.contains(POST_DELTA_STAMP_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(postDeltaStampFailedLine(faultKey, FAILURE_TEXT), line.message)
    }

    @Test
    fun aPostDeltaStampErrorStaysLoggedAndLaterKeysStamp() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val store = seededStore(PostDeltaStampProbeError())
        val service = loggedContactService(store, logs)
        val faultKey = publicKey(FAULT_KEY_BYTE)
        val laterKey = publicKey(LATER_KEY_BYTE)

        service.stampAdvertReceiveTimes(drainedKeys(faultKey, laterKey), receiveTimes(faultKey, laterKey), radioID)

        assertEquals(PRIOR_HEARD, store.fetchContact(radioID, faultKey)?.lastHeardTimestamp)
        assertEquals(RECEIVED_AT, store.fetchContact(radioID, laterKey)?.lastHeardTimestamp)
        assertEquals(
            postDeltaStampFailedLine(faultKey, PROBE_TEXT),
            logs.single { it.message.contains(POST_DELTA_STAMP_FAILED) }.message,
        )
    }

    private suspend fun seededStore(touchError: Throwable): PostDeltaStampFaultStore {
        val store = PostDeltaStampFaultStore(InMemoryContactStore(), publicKey(FAULT_KEY_BYTE), touchError)
        seed(store, FAULT_KEY_BYTE, "Delta")
        seed(store, LATER_KEY_BYTE, "Later")
        return store
    }

    private suspend fun seed(store: PostDeltaStampFaultStore, keyByte: Int, name: String) {
        store.saveContact(radioID, contactFrame(keyByte, name = name))
        val saved = store.fetchContact(radioID, publicKey(keyByte))!!
        store.saveContact(saved.copy(lastHeardTimestamp = PRIOR_HEARD))
    }

    private fun drainedKeys(faultKey: ByteArray, laterKey: ByteArray): List<ByteArray> = listOf(faultKey, laterKey)

    private fun receiveTimes(faultKey: ByteArray, laterKey: ByteArray): Map<String, Long> = mapOf(
        faultKey.toUppercaseHex() to RECEIVED_AT,
        laterKey.toUppercaseHex() to RECEIVED_AT,
    )
}

private const val FAULT_KEY_BYTE = 0x37
private const val LATER_KEY_BYTE = 0x38
private const val PRIOR_HEARD = 1L
private const val RECEIVED_AT = 1_699_999_000L
private const val POST_DELTA_STAMP_FAILED = "Post-delta lastHeard stamp failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun postDeltaStampFailedLine(key: ByteArray, detail: String): String =
    "$POST_DELTA_STAMP_FAILED for ${key.toUppercaseHex()}: $detail"

private class PostDeltaStampFaultStore(
    private val inner: InMemoryContactStore,
    private val faultKey: ByteArray,
    private val touchError: Throwable,
) : ContactStore by inner {
    override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean {
        if (publicKey.contentEquals(faultKey)) throw touchError
        return inner.touchContactHeard(radioID, publicKey, atEpochSeconds)
    }
}

private class PostDeltaStampFailure : Exception("disk")

private class PostDeltaStampProbeError : Error("probe")
