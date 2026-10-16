package one.meshcore.shared.tools.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class RxLogCopyIconTest {
    @Test
    fun idleCopyUsesTheDocumentIcon() {
        assertEquals(AppIcons.ContentCopy, rxLogCopyActionIcon(copied = false))
    }

    @Test
    fun copiedFlashUsesTheCheckmark() {
        assertEquals(AppIcons.Check, rxLogCopyActionIcon(copied = true))
    }
}
