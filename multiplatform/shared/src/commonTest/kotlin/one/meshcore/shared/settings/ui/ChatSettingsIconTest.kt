package one.meshcore.shared.settings.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSettingsIconTest {
    @Test
    fun replyWithQuoteUsesTextQuoteAnalog() {
        assertEquals(AppIcons.FormatQuote, CHAT_SETTINGS_REPLY_WITH_QUOTE_ICON)
    }

    @Test
    fun linkPreviewsUsesLink() {
        assertEquals(AppIcons.Link, CHAT_SETTINGS_LINK_PREVIEWS_ICON)
    }

    @Test
    fun mapPreviewsUsesMap() {
        assertEquals(AppIcons.MapSelected, CHAT_SETTINGS_MAP_PREVIEWS_ICON)
    }
}
