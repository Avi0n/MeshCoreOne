package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_wifi_connection_failed
import one.meshcore.shared.resources.localizable_error_wifi_connection_timeout
import one.meshcore.shared.resources.localizable_error_wifi_invalid_host
import one.meshcore.shared.resources.localizable_error_wifi_invalid_port
import one.meshcore.shared.resources.localizable_error_wifi_not_connected
import one.meshcore.shared.resources.localizable_error_wifi_send_failed
import one.meshcore.shared.resources.localizable_error_wifi_send_timeout
import one.meshcore.shared.transport.TcpTransportException
import org.jetbrains.compose.resources.StringResource

/**
 * The shipped strings for a WiFi/TCP transport failure. Declared as a table so the set a caller resolves
 * ahead of the failure derives from it rather than being listed a second time, where a new message could
 * be missed and leave the user with the exception's developer English.
 */
internal enum class TcpTransportErrorText(val resource: StringResource) {
    CONNECTION_FAILED(Res.string.localizable_error_wifi_connection_failed),
    CONNECTION_TIMEOUT(Res.string.localizable_error_wifi_connection_timeout),
    NOT_CONNECTED(Res.string.localizable_error_wifi_not_connected),
    SEND_FAILED(Res.string.localizable_error_wifi_send_failed),
    SEND_TIMEOUT(Res.string.localizable_error_wifi_send_timeout),
    INVALID_HOST(Res.string.localizable_error_wifi_invalid_host),
    INVALID_PORT(Res.string.localizable_error_wifi_invalid_port),
    ;

    companion object {
        /** Every string [messageResource] can return, for a caller resolving them before the failure happens. */
        val allResources: Set<StringResource> = entries.map { it.resource }.toSet()
    }
}

/**
 * The message for a transport failure, the Kotlin counterpart of the Swift
 * `WiFiTransportError.errorDescription`. Each case carries developer English in its constructor
 * ("invalid host", "connection timed out"). The connect sheet maps through this table, then a
 * settings/bridge fallback, so an unmapped BLE fault does not show JNI text.
 *
 * Exhaustive on purpose: a case added to the transport is a compile error here rather than a silent
 * fallback to English.
 */
internal fun TcpTransportException.errorText(): TcpTransportErrorText = when (this) {
    is TcpTransportException.ConnectionFailed -> TcpTransportErrorText.CONNECTION_FAILED
    is TcpTransportException.ConnectionTimeout -> TcpTransportErrorText.CONNECTION_TIMEOUT
    is TcpTransportException.NotConnected -> TcpTransportErrorText.NOT_CONNECTED
    is TcpTransportException.SendFailed -> TcpTransportErrorText.SEND_FAILED
    is TcpTransportException.SendTimeout -> TcpTransportErrorText.SEND_TIMEOUT
    is TcpTransportException.InvalidHost -> TcpTransportErrorText.INVALID_HOST
    is TcpTransportException.InvalidPort -> TcpTransportErrorText.INVALID_PORT
}

fun TcpTransportException.messageResource(): StringResource = errorText().resource

/**
 * Turns a transport failure into text for the connect sheet, which catches inside a coroutine and cannot
 * read resources at the point of failure. Answers null for anything that is not a
 * [TcpTransportException], leaving the caller's existing fallback in place.
 */
@Composable
fun rememberTcpTransportErrorText(): (Throwable) -> String? = rememberMappedErrorText(
    TcpTransportErrorText.allResources,
    { it as? TcpTransportException },
    TcpTransportException::messageResource,
    TcpTransportException::formatArgs,
)

/** The substitutions each message expects; only the two failures that quote a socket reason take one. */
internal fun TcpTransportException.formatArgs(): List<Any> = when (this) {
    is TcpTransportException.ConnectionFailed -> listOf(reason)
    is TcpTransportException.SendFailed -> listOf(reason)
    else -> emptyList()
}
