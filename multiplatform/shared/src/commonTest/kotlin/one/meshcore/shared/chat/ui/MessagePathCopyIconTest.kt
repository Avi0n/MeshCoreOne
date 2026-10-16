package one.meshcore.shared.chat.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagePathCopyIconTest {
    @Test
    fun hopHexCopyUsesTheDocumentIcon() {
        assertEquals(AppIcons.ContentCopy, PATH_COPY_ICON)
    }
}
