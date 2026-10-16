package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.navigation.SendAdvertShortcutOutcome

/**
 * Shell-owned radio status for top app bars on tabs that show the connection indicator
 * (Chats, Nodes, Map). Provided by [MainShellWithChrome]; app bars append [ShellConnectionAction]
 * so the control is a real trailing action — not a shell overlay that steals layout width.
 */
@Immutable
data class ShellConnectionChromeState(
    val visible: Boolean,
    val connectionState: DeviceConnectionState,
    val battery: BatteryInfo?,
    val batteryOcvArray: List<Int>,
    val nodeName: String?,
    val clientRepeat: Boolean,
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onChangeDevice: () -> Unit,
    val onSendAdvert: suspend (flood: Boolean) -> SendAdvertShortcutOutcome,
    val onAdvancedSettings: () -> Unit,
)

/** Null when outside the main shell (previews, tests). */
val LocalShellConnectionChrome = compositionLocalOf<ShellConnectionChromeState?> { null }

/**
 * Trailing radio status menu for [androidx.compose.material3.TopAppBar] actions.
 * No-op when the local is null or [ShellConnectionChromeState.visible] is false.
 */
@Composable
fun ShellConnectionAction() {
    val chrome = LocalShellConnectionChrome.current ?: return
    if (!chrome.visible) return
    ConnectionChrome(
        connectionState = chrome.connectionState,
        battery = chrome.battery,
        batteryOcvArray = chrome.batteryOcvArray,
        nodeName = chrome.nodeName,
        clientRepeat = chrome.clientRepeat,
        onConnect = chrome.onConnect,
        onDisconnect = chrome.onDisconnect,
        onChangeDevice = chrome.onChangeDevice,
        onSendAdvert = chrome.onSendAdvert,
        onAdvancedSettings = chrome.onAdvancedSettings,
    )
}
