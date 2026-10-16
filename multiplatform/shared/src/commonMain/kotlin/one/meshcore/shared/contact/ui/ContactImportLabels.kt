package one.meshcore.shared.contact.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.contact.AddContactError
import one.meshcore.shared.contact.PUBLIC_KEY_HEX_LENGTH
import one.meshcore.shared.contact.ScanContactError
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_add_error_invalid_format
import one.meshcore.shared.resources.contacts_add_error_invalid_size
import one.meshcore.shared.resources.contacts_add_error_node_list_full
import one.meshcore.shared.resources.contacts_add_error_node_list_full_simple
import one.meshcore.shared.resources.contacts_add_error_not_connected
import one.meshcore.shared.resources.contacts_common_error
import one.meshcore.shared.resources.contacts_scan_error_import_failed
import one.meshcore.shared.resources.contacts_scan_error_invalid_format
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

/** The user-facing message for an Add-contact form error. */
@Composable
fun AddContactError.message(): String = when (this) {
    AddContactError.NotConnected -> stringResource(Res.string.contacts_add_error_not_connected)
    AddContactError.InvalidFormat -> stringResource(Res.string.contacts_add_error_invalid_format)
    AddContactError.InvalidSize -> formattedStringResource(
        Res.string.contacts_add_error_invalid_size,
        ChatConstants.PUBLIC_KEY_SIZE_BYTES,
        PUBLIC_KEY_HEX_LENGTH,
    )
    is AddContactError.NodeListFull -> nodeListFullMessage(maxContacts)
    is AddContactError.ServiceMessage -> "${stringResource(Res.string.contacts_common_error)}: $message"
}

/** The user-facing message for a QR-scan error. */
@Composable
fun ScanContactError.message(): String = when (this) {
    ScanContactError.InvalidFormat -> stringResource(Res.string.contacts_scan_error_invalid_format)
    ScanContactError.NotConnected -> stringResource(Res.string.contacts_add_error_not_connected)
    is ScanContactError.NodeListFull -> nodeListFullMessage(maxContacts)
    is ScanContactError.ImportFailed -> formattedStringResource(Res.string.contacts_scan_error_import_failed, message)
}

/** "Node list is full (max N nodes)" when the cap is known, else the cap-free variant. */
@Composable
private fun nodeListFullMessage(maxContacts: Int?): String = if (maxContacts != null) {
    formattedStringResource(Res.string.contacts_add_error_node_list_full, maxContacts)
} else {
    stringResource(Res.string.contacts_add_error_node_list_full_simple)
}
