package one.meshcore.shared.transport

/**
 * The WiFi/TCP transport error taxonomy, mirroring the Swift `WiFiTransportError` cases as a sealed
 * hierarchy so callers can branch on the specific failure. [ConnectionTimeout] and [ConnectionFailed]
 * surface from [TcpMeshLink.connect]; the send-side cases classify a write failure that tears the link
 * down; [InvalidHost]/[InvalidPort] are pre-connect validation failures.
 */
sealed class TcpTransportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The TCP connection could not be established (socket error, refused, unreachable). */
    class ConnectionFailed(val reason: String, cause: Throwable? = null) :
        TcpTransportException("connection failed: $reason", cause)

    /** The connection did not complete within the connect timeout. */
    class ConnectionTimeout : TcpTransportException("connection timed out")

    /** A send was attempted with no live connection. */
    class NotConnected : TcpTransportException("not connected")

    /** A write failed at the socket layer. */
    class SendFailed(val reason: String) : TcpTransportException("send failed: $reason")

    /** A write did not complete within the write timeout. */
    class SendTimeout : TcpTransportException("send timed out")

    /** The configured host is empty or malformed. */
    class InvalidHost : TcpTransportException("invalid host")

    /** The configured port is outside the valid 1..65535 range. */
    class InvalidPort : TcpTransportException("invalid port")
}
