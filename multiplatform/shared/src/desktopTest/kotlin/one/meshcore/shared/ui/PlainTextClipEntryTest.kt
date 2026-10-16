package one.meshcore.shared.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PlainTextClipEntryTest {
    @Test
    fun nativeTextTransferRetainsEmptyUnicodeAndMultilineContent() {
        for (text in listOf("", "A3FF0012", "日本語 😀\nsecond line")) {
            val entry = plainTextClipEntry(text)
            val transferable = assertIs<Transferable>(entry.nativeClipEntry)
            assertTrue(transferable.isDataFlavorSupported(DataFlavor.stringFlavor))
            assertEquals(text, transferable.getTransferData(DataFlavor.stringFlavor))
            assertEquals(text, entry.plainText())
        }
    }
}
