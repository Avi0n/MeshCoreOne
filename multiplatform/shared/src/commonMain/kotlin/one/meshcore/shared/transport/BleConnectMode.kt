package one.meshcore.shared.transport

import kotlin.time.Duration

/**
 * Android GATT connect mode. iOS sets `CBConnectPeripheralOptionEnableAutoReconnect` on every connect
 * so the OS pending-connect survives an out-of-range radio. Android has no equivalent unless
 * `connectGatt(..., autoConnect=true)` is used, and that wait is indefinite — so a reconnect must
 * not keep the 10s direct-connect GATT watchdog.
 */
object BleConnectMode {
    fun gattConnectTimeout(autoConnect: Boolean): Duration =
        if (autoConnect) Duration.INFINITE else BleTransport.DEFAULT_GATT_CONNECT_TIMEOUT
}
