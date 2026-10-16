package one.meshcore.shared.chat.text

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The hashtag tap as the shell dispatches it: the tapped name is looked up among the radio's configured
 * channels before anything is staged, so tapping `#wardriving` while already in `#wardriving` opens that
 * thread instead of offering to join it a second time. Mirrors the Swift `ChatLinkRouter.handleHashtagTap`.
 */
class HashtagTapDispatchTest {
    @Test
    fun aTapOnAChannelAlreadyJoinedOpensIt() = runTest {
        val result = resolveHashtagTap(
            rawName = "wardriving",
            hasRadio = true,
            fetchChannels = { listOf(HashtagChannelRef(id = "chan-3", name = "#wardriving")) },
        )
        assertEquals(HashtagTapResult.OpenChannel("chan-3"), result)
    }

    @Test
    fun theLookupIsCaseInsensitiveOnTheNormalizedName() = runTest {
        val result = resolveHashtagTap(
            rawName = "#WarDriving",
            hasRadio = true,
            fetchChannels = { listOf(HashtagChannelRef(id = "chan-3", name = "#wardriving")) },
        )
        assertEquals(HashtagTapResult.OpenChannel("chan-3"), result)
    }

    @Test
    fun anUnjoinedNameStagesTheJoinSheet() = runTest {
        val result = resolveHashtagTap(
            rawName = "weather",
            hasRadio = true,
            fetchChannels = { listOf(HashtagChannelRef(id = "chan-3", name = "#wardriving")) },
        )
        assertEquals(HashtagTapResult.StageJoin("#weather"), result)
    }

    @Test
    fun noRadioStagesTheJoinWithoutFetching() = runTest {
        var fetched = false
        val result = resolveHashtagTap(
            rawName = "weather",
            hasRadio = false,
            fetchChannels = {
                fetched = true
                emptyList()
            },
        )
        assertEquals(HashtagTapResult.StageJoin("#weather"), result)
        assertFalse(fetched, "A tap with no radio must not query the channel store")
    }

    @Test
    fun aFailedChannelFetchFallsBackToTheJoinSheet() = runTest {
        val result = resolveHashtagTap(
            rawName = "weather",
            hasRadio = true,
            fetchChannels = { error("store unavailable") },
        )
        assertEquals(HashtagTapResult.StageJoin("#weather"), result)
    }

    @Test
    fun aCancelledChannelFetchPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            resolveHashtagTap(
                rawName = "weather",
                hasRadio = true,
                fetchChannels = { throw cancelled },
            )
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun aChannelFetchErrorPropagates() = runTest {
        val fault = HashtagFetchProbeError()
        val thrown = assertFailsWith<HashtagFetchProbeError> {
            resolveHashtagTap(
                rawName = "weather",
                hasRadio = true,
                fetchChannels = { throw fault },
            )
        }
        assertSame(fault, thrown)
    }

    @Test
    fun anInvalidNameIsRejectedBeforeAnyFetch() = runTest {
        var fetched = false
        val result = resolveHashtagTap(
            rawName = "bad name",
            hasRadio = true,
            fetchChannels = {
                fetched = true
                emptyList()
            },
        )
        assertEquals(HashtagTapResult.Invalid, result)
        assertFalse(fetched, "An unusable hashtag name must not query the channel store")
        assertTrue(HashtagDeeplink.fullChannelName("bad name") == null)
    }
}

private class HashtagFetchProbeError : Error("probe")
