package one.meshcore.shared.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelSlotsTest {
    @Test
    fun available_slots_exclude_used_and_reserve_slot_zero() {
        assertEquals(listOf(1, 3, 4), ChannelSlots.availableSlots(usedSlots = setOf(0, 2), maxChannels = 5))
    }

    @Test
    fun available_slots_never_include_slot_zero_even_when_free() {
        // Slot 0 is the public channel and is never offered as a user slot.
        assertFalse(0 in ChannelSlots.availableSlots(usedSlots = emptySet(), maxChannels = 4))
    }

    @Test
    fun available_slots_are_empty_when_the_device_reports_one_or_fewer_channels() {
        assertEquals(emptyList(), ChannelSlots.availableSlots(usedSlots = emptySet(), maxChannels = 1))
        assertEquals(emptyList(), ChannelSlots.availableSlots(usedSlots = emptySet(), maxChannels = 0))
    }

    @Test
    fun has_public_channel_tracks_slot_zero() {
        assertTrue(ChannelSlots.hasPublicChannel(setOf(0, 1)))
        assertFalse(ChannelSlots.hasPublicChannel(setOf(1, 2)))
    }
}
