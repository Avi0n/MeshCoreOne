package one.meshcore.shared.ui

import androidx.compose.ui.platform.ClipEntry

/** A platform clipboard entry containing the supplied plain text verbatim. */
internal expect fun plainTextClipEntry(text: String): ClipEntry

/** The entry's plain text, or null when it does not carry text. */
internal expect fun ClipEntry.plainText(): String?
