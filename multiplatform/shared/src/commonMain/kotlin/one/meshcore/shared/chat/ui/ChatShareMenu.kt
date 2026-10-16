package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_input_share_button_accessibility_label
import one.meshcore.shared.resources.chats_share_contact
import one.meshcore.shared.resources.chats_share_location
import one.meshcore.shared.resources.chats_share_my_info
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuItem
import org.jetbrains.compose.resources.stringResource

/**
 * Leading "+" menu on the chat composer: share location, insert a contact-share token, or share own
 * contact. Mirrors iOS `ChatShareMenu` actions; each action emits a bare string via [onInsert].
 */
@Composable
fun ChatShareMenu(
    canShareLocation: Boolean,
    canShareMyInfo: Boolean,
    onShareLocation: () -> Unit,
    onShareContact: () -> Unit,
    onShareMyInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val shareLabel = stringResource(Res.string.chats_input_share_button_accessibility_label)
    Box(modifier) {
        FilledTonalIconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = shareLabel },
        ) {
            Icon(
                imageVector = AppIcons.Add,
                contentDescription = null,
            )
        }
        McDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            McMenuItem(
                text = stringResource(Res.string.chats_share_location),
                leadingIcon = AppIcons.LocationOn,
                enabled = canShareLocation,
                onClick = {
                    expanded = false
                    onShareLocation()
                },
            )
            McMenuItem(
                text = stringResource(Res.string.chats_share_contact),
                leadingIcon = AppIcons.Person,
                onClick = {
                    expanded = false
                    onShareContact()
                },
            )
            McMenuItem(
                text = stringResource(Res.string.chats_share_my_info),
                leadingIcon = AppIcons.PersonAdd,
                enabled = canShareMyInfo,
                onClick = {
                    expanded = false
                    onShareMyInfo()
                },
            )
        }
    }
}
