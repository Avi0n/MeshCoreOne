package one.meshcore.shared.ui

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal class HoldingClipboard : Clipboard {
    val release = CompletableDeferred<Unit>()
    val entries = mutableListOf<ClipEntry?>()
    var readEntry: ClipEntry? = null
    var readsAllowed = false
    var completed = 0
    var cancelled = 0
    var reads = 0

    override suspend fun getClipEntry(): ClipEntry? {
        if (!readsAllowed) error("Copy must not read the clipboard")
        reads += 1
        val entry = readEntry
        awaitRelease()
        return entry
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        entries += clipEntry
        awaitRelease()
    }

    private suspend fun awaitRelease() {
        try {
            release.await()
        } catch (stopped: CancellationException) {
            cancelled++
            throw stopped
        }
        completed++
    }
}
