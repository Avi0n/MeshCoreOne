package one.meshcore.shared.ui

/**
 * Single shell entry for radio connect / change-device / disconnected-pill actions (RAD-1).
 * Must open [ShellSheet.ConnectRadio], never switch to the Nodes tab.
 */
object RadioConnectEntry {
    /** The modal sheet that hosts BLE scan + WiFi connect. */
    val sheet: ShellSheet = ShellSheet.ConnectRadio
}
