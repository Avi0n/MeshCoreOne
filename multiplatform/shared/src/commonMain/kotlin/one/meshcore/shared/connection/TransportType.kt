package one.meshcore.shared.connection

/**
 * How the live connection reaches the radio. Selects the reconnect schedule on a link drop — BLE uses
 * [ReconnectPolicy] (30/60/120s), WiFi uses [WiFiReconnectPolicy] (0.5→4s capped, 35s cooldown) — and
 * gates the WiFi-only heartbeat. Mirrors Swift's `TransportType`.
 */
enum class TransportType {
    BLUETOOTH,
    WIFI,
}
