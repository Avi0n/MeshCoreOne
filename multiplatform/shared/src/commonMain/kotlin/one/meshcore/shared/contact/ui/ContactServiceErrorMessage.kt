package one.meshcore.shared.contact.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.contact.ContactDeviceException
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.localizable_error_contact_service_contact_not_found
import one.meshcore.shared.resources.localizable_error_contact_service_contact_table_full
import one.meshcore.shared.resources.localizable_error_contact_service_invalid_response
import one.meshcore.shared.resources.localizable_error_contact_service_not_connected
import one.meshcore.shared.resources.localizable_error_contact_service_send_failed
import one.meshcore.shared.resources.localizable_error_contact_service_share_contact_unavailable
import one.meshcore.shared.resources.localizable_error_contact_service_sync_interrupted
import one.meshcore.shared.resources.localizable_error_device_unknown
import one.meshcore.shared.ui.MeshBridgeErrorMessage
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.rememberFallbackErrorText
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The user-facing message for a contact-service failure, the Kotlin analogue of the Swift
 * `ContactServiceError.errorDescription`. Without this the exception's own text reaches the screen, and
 * that text is English no matter what locale the device runs in.
 */
@Composable
fun ContactServiceError.userFacingMessage(): String {
    val resource = messageResource() ?: Res.string.contacts_common_error_occurred
    return formatTemplate(stringResource(resource), messageFormatArgs())
}

/** Resource [userFacingMessage] renders; unmapped session failures use errorOccurred, not English text. */
internal fun ContactServiceError.userFacingResource(): StringResource =
    messageResource() ?: Res.string.contacts_common_error_occurred

/**
 * The translated string for a failure, or null when only the exception's own text describes it. A
 * wrapped session failure resolves through its cause, as the Swift `.sessionError(MeshCoreError)` case
 * does. Pure, so the key mapping is asserted without a composition.
 */
fun ContactServiceError.messageResource(): StringResource? = when (this) {
    is ContactServiceError.NotConnected -> Res.string.localizable_error_contact_service_not_connected
    is ContactServiceError.SendFailed -> Res.string.localizable_error_contact_service_send_failed
    is ContactServiceError.InvalidResponse -> Res.string.localizable_error_contact_service_invalid_response
    is ContactServiceError.SyncInterrupted -> Res.string.localizable_error_contact_service_sync_interrupted
    is ContactServiceError.ContactNotFound -> Res.string.localizable_error_contact_service_contact_not_found
    is ContactServiceError.ContactTableFull -> Res.string.localizable_error_contact_service_contact_table_full
    is ContactServiceError.ShareContactUnavailable ->
        Res.string.localizable_error_contact_service_share_contact_unavailable
    is ContactServiceError.SessionError -> when (val cause = this.cause) {
        is MeshBridgeException -> MeshBridgeErrorMessage.resource(cause)
        is ContactDeviceException -> MeshBridgeErrorMessage.deviceResource(cause.firmwareCode)
        else -> null
    }
}

/** The substitutions [messageResource] expects, left to right; empty for a fixed string. */
fun ContactServiceError.messageFormatArgs(): List<Any> = when (this) {
    is ContactServiceError.SessionError -> when (val cause = this.cause) {
        is MeshBridgeException -> MeshBridgeErrorMessage.formatArgs(cause)
        is ContactDeviceException -> {
            val unknown = MeshBridgeErrorMessage.deviceResource(cause.firmwareCode) ==
                Res.string.localizable_error_device_unknown
            if (unknown) listOf(cause.firmwareCode) else emptyList()
        }
        else -> emptyList()
    }
    else -> emptyList()
}

/** Every fixed string [messageResource] can return, so a caller can resolve them before it needs them. */
private val CONTACT_ERROR_RESOURCES: Set<StringResource> = setOf(
    Res.string.localizable_error_contact_service_not_connected,
    Res.string.localizable_error_contact_service_send_failed,
    Res.string.localizable_error_contact_service_invalid_response,
    Res.string.localizable_error_contact_service_sync_interrupted,
    Res.string.localizable_error_contact_service_contact_not_found,
    Res.string.localizable_error_contact_service_contact_table_full,
    Res.string.localizable_error_contact_service_share_contact_unavailable,
)

/**
 * Turns a contact-service failure into text for a view model, which cannot read string resources itself.
 * Every candidate string is resolved during composition and handed over as a plain function, the shape
 * the view-model dependency surface already uses. Unmapped failures use
 * [Res.string.contacts_common_error_occurred] rather than English `"Session error"` or the exception
 * text, matching iOS `contacts.common.errorOccurred`.
 */
@Composable
fun rememberContactServiceErrorText(): (Throwable) -> String {
    val unmapped = stringResource(Res.string.contacts_common_error_occurred)
    return rememberFallbackErrorText(
        CONTACT_ERROR_RESOURCES + MeshBridgeErrorMessage.allResources,
        { it as? ContactServiceError },
        ContactServiceError::messageResource,
        ContactServiceError::messageFormatArgs,
        unmapped,
    )
}
