package one.meshcore.shared.connection

/**
 * Why the manager is dropping the live connection, ported from the Swift `DisconnectReason` and reduced to
 * the reasons Kotlin drives today.
 *
 * The split is load-bearing rather than diagnostic: only a reason the user chose clears the connection
 * intent. A resync failure preserves it, so the radio is still wanted and a later attempt may recover it.
 */
enum class DisconnectReason {
    /** The user asked to disconnect. The only reason that suppresses auto-reconnect afterwards. */
    USER_INITIATED,

    /** Temporarily releases the radio while a new-device chooser owns connection decisions. */
    PAIRING_NEW_DEVICE,

    /** The post-connect resync spent its attempt budget without reaching a synced connection. */
    RESYNC_FAILED,

    /**
     * Settings saved a new WiFi host/port. The live TCP session is torn down so the next connect
     * uses the new address. The radio stays wanted (iOS `wifiAddressChange`).
     */
    WIFI_ADDRESS_CHANGE,
    ;

    /** Whether this disconnect means the user wants to stay disconnected. */
    val clearsUserIntent: Boolean
        get() = this == USER_INITIATED
}
