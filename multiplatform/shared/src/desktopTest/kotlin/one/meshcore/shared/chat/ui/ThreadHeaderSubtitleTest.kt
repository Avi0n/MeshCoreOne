package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.ChannelFloodScopeStorage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The conversation header's second line for both conversation kinds. A direct-message thread must state how
 * the next message travels — flood or a stored out-path — because that is the only place the user sees it
 * before sending. Mirrors Swift `ChatConversationType.navigationSubtitle`.
 */
class ThreadHeaderSubtitleTest {
    private val floodRoutedContact = ChatFixtures.contact("Alice").copy(outPathLength = FLOOD_PATH_SENTINEL)

    @Test
    fun directMessageWithNoOutPathReportsFloodRouting() {
        assertEquals(
            ThreadHeaderSubtitle.FloodRouting,
            threadHeaderSubtitle(channel = null, contact = floodRoutedContact, deviceDefaultFloodScopeName = null),
        )
    }

    @Test
    fun directMessageWithAnOutPathReportsItsHopCount() {
        val routed = ChatFixtures.contact("Alice").copy(outPathLength = TWO_HOP_PATH_LENGTH)
        assertEquals(
            ThreadHeaderSubtitle.Direct(hops = 2),
            threadHeaderSubtitle(channel = null, contact = routed, deviceDefaultFloodScopeName = null),
        )
    }

    @Test
    fun directMessageWithAZeroHopPathStillReportsDirect() {
        val routed = ChatFixtures.contact("Alice").copy(outPathLength = 0)
        assertEquals(
            ThreadHeaderSubtitle.Direct(hops = 0),
            threadHeaderSubtitle(channel = null, contact = routed, deviceDefaultFloodScopeName = null),
        )
    }

    @Test
    fun channelReportsItsRegionAndMarksTheRadioDefault() {
        val channel = regionChannel("EU")
        assertEquals(
            ThreadHeaderSubtitle.Region("EU", isDeviceDefault = false),
            threadHeaderSubtitle(channel = channel, contact = null, deviceDefaultFloodScopeName = "US"),
        )
        assertEquals(
            ThreadHeaderSubtitle.Region("EU", isDeviceDefault = true),
            threadHeaderSubtitle(channel = channel, contact = null, deviceDefaultFloodScopeName = "EU"),
        )
    }

    @Test
    fun channelWithNoResolvableRegionHasNoSubtitle() {
        assertEquals(
            ThreadHeaderSubtitle.None,
            threadHeaderSubtitle(
                channel = ChatFixtures.channel("General"),
                contact = null,
                deviceDefaultFloodScopeName = null,
            ),
        )
    }

    @Test
    fun aChannelThreadNeverReadsTheContactBranch() {
        val channel = regionChannel("EU")
        assertEquals(
            ThreadHeaderSubtitle.Region("EU", isDeviceDefault = false),
            threadHeaderSubtitle(channel = channel, contact = floodRoutedContact, deviceDefaultFloodScopeName = null),
        )
    }

    /** A channel pinned to [region], stored the way the backup wire format carries a specific scope. */
    private fun regionChannel(region: String) = ChatFixtures.channel("General")
        .copy(floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_SPECIFIC, regionScope = region)

    private companion object {

        /** `outPathLength` sentinel meaning "no stored route; flood". */
        const val FLOOD_PATH_SENTINEL = 0xFF

        /** Hash-size mode 0 (one byte per hop) with two hops in the low six bits. */
        const val TWO_HOP_PATH_LENGTH = 2
    }
}
