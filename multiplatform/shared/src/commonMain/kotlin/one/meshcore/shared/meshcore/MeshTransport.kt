package one.meshcore.shared.meshcore

/**
 * The physical radio link, as seen by the session.
 *
 * The session writes outbound frames through [sendBytes]; the link implementation (Kable BLE or the
 * shared JVM TCP socket) drives the session's inbound hooks
 * ([MeshCoreSession.deliverReceived], [MeshCoreSession.reportConnected], and friends) as bytes
 * arrive and the connection state changes. This mirrors the Swift `MeshTransport` split: `send`
 * out, `receivedData` plus the connect/disconnect signals in.
 */
interface MeshTransport {
    /**
     * Writes one complete frame to the link. `withoutResponse` mirrors MeshCore's acknowledged vs
     * unacknowledged write (BLE Write Request vs Write Command); a link that cannot honor it treats
     * both the same.
     */
    fun sendBytes(bytes: ByteArray, withoutResponse: Boolean)
}
