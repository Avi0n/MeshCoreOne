package one.meshcore.shared.room.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_error_pin_save_failed
import one.meshcore.shared.resources.chats_row_pin
import one.meshcore.shared.settings.ui.SettingsToggleRow
import one.meshcore.shared.ui.FormErrorRow
import org.jetbrains.compose.resources.stringResource

/** A local room pin remains editable offline; an unfinished write blocks overlapping toggles. */
@Composable
internal fun RoomInfoPinPreference(session: RemoteNodeSessionDto, onPinnedChange: suspend (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var isPinned by remember(session.id, session.radioID, session.isPinned) { mutableStateOf(session.isPinned) }
    var isSavingPin by remember(session.id, session.radioID) { mutableStateOf(false) }
    var errorMessage by remember(session.id, session.radioID) { mutableStateOf<String?>(null) }
    val pinSaveFailed = stringResource(Res.string.chats_error_pin_save_failed)

    SettingsToggleRow(
        label = stringResource(Res.string.chats_row_pin),
        checked = isPinned,
        enabled = !isSavingPin,
        onCheckedChange = { next ->
            if (isSavingPin) return@SettingsToggleRow
            isSavingPin = true
            val previous = isPinned
            isPinned = next
            scope.launch {
                var saved = false
                try {
                    saved = persistRoomInfoChange { onPinnedChange(next) }
                    errorMessage = if (saved) null else pinSaveFailed
                } finally {
                    if (!saved) isPinned = previous
                    isSavingPin = false
                }
            }
        },
    )
    errorMessage?.let { FormErrorRow(it) }
}
