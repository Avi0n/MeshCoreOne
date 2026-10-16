package one.meshcore.shared.channel

import one.meshcore.shared.channel.ui.ChannelAvatarGlyph
import one.meshcore.shared.channel.ui.channelAvatarGlyph
import kotlin.test.Test
import kotlin.test.assertEquals

/** The channel-avatar glyph selection, mirroring iOS `ChannelAvatar` (globe / number / lock). */
class ChannelAvatarGlyphTest {
    @Test
    fun slot_zero_is_the_public_globe() {
        val channel = channelDto(ChannelTestIds.fresh(), ChannelTestIds.RADIO, index = 0, name = "Public")
        assertEquals(ChannelAvatarGlyph.PUBLIC, channelAvatarGlyph(channel))
    }

    @Test
    fun a_hashtag_name_is_the_number_glyph() {
        val channel = channelDto(ChannelTestIds.fresh(), ChannelTestIds.RADIO, index = 3, name = "#alps")
        assertEquals(ChannelAvatarGlyph.HASHTAG, channelAvatarGlyph(channel))
    }

    @Test
    fun a_named_private_channel_is_the_lock() {
        val channel = channelDto(ChannelTestIds.fresh(), ChannelTestIds.RADIO, index = 3, name = "Ops")
        assertEquals(ChannelAvatarGlyph.PRIVATE, channelAvatarGlyph(channel))
    }
}
