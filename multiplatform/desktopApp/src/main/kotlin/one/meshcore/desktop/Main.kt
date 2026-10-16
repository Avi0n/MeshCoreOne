package one.meshcore.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import one.meshcore.shared.AppInfo
import one.meshcore.shared.ui.AppRoot

/**
 * The desktop entry point. It renders the same adaptive [AppShell] as Android (a wide window lands on the
 * list-detail layout) and maps the window's focus to the scene lifecycle, the desktop counterpart of the
 * Android `ON_START`/`ON_STOP` observer. A `meshcore://` URL passed as the first argument is staged and
 * routed once launch settles.
 */
fun main(args: Array<String>) = application {
    val container = remember { DesktopContainer() }

    LaunchedEffect(Unit) {
        args.firstOrNull { it.startsWith("meshcore") }?.let(container::submitDeepLink)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = AppInfo.APP_NAME,
    ) {
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused) {
            if (focused) container.lifecycle.onEnterForeground() else container.lifecycle.onEnterBackground()
        }
        AppRoot(deps = container.deps)
    }
}
