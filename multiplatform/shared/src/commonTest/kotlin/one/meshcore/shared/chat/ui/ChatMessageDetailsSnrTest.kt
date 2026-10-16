package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMessageDetailsSnrTest {
    @Test
    fun visibleTextUsesTheInjectedUnit() {
        assertEquals(
            "-3.0 дБ (Fair)",
            messageDetailsSnrVisibleText(
                snrFormatted = "-3.0",
                unit = "дБ",
                quality = "Fair",
            ),
        )
    }
}
