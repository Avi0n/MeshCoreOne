package one.meshcore.shared.app

/** Which foreground/launch auto-reconnect path [AppStateViewModel.activate] took. */
enum class AutoReconnectDecision {
    /** The user last disconnected on purpose; nothing reconnects and the pill stays hidden. */
    SKIP_USER_DISCONNECTED,

    /** The connect/pair sheet owns the radio; last-radio activate must not start GATT. */
    SKIP_PAIRING_IN_PROGRESS,

    /** No last-connected device on record; a brand-new or forgotten install. */
    NOTHING_TO_RECONNECT,

    /** A WiFi reachability descriptor drove a `connectViaWiFi`. */
    RECONNECT_WIFI,

    /** No usable WiFi method; a BLE reconnect was attempted against the persisted handle. */
    RECONNECT_BLE,

    /** Last connection was the bundled simulator/demo radio. */
    RECONNECT_SIMULATOR,
}
