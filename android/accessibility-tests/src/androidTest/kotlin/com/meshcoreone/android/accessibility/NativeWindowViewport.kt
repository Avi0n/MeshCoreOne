// AndroidOnly: WP-503 Observe actual dialog/activity safe viewports without substituting semantic-root size for keyboard visibility.
package com.meshcoreone.android.accessibility

import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.ComposeTestRule

internal fun nativeWindowViewport(compose: ComposeTestRule, host: View, node: SemanticsNode): Rect {
    val frame = android.graphics.Rect()
    val screen = IntArray(2)
    val window = IntArray(2)
    var insetFrame = Rect.Zero
    compose.runOnUiThread {
        host.getWindowVisibleDisplayFrame(frame)
        host.getLocationOnScreen(screen)
        host.getLocationInWindow(window)
        val metrics = requireNotNull(host.context.getSystemService(WindowManager::class.java)).currentWindowMetrics
        val safe = requireNotNull(host.rootWindowInsets)
            .getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val bounds = metrics.bounds
        insetFrame = Rect(
            (bounds.left + safe.left).toFloat(), (bounds.top + safe.top).toFloat(),
            (bounds.right - safe.right).toFloat(), (bounds.bottom - safe.bottom).toFloat(),
        )
        println("WP503_NATIVE_WINDOW_VIEWPORT|window=$bounds|safe=$safe|visibleFrame=$frame")
    }
    val native = Rect(frame.left.toFloat(), frame.top.toFloat(), frame.right.toFloat(), frame.bottom.toFloat())
        .intersect(insetFrame).translate((window[0] - screen[0]).toFloat(), (window[1] - screen[1]).toFloat())
    return generateSequence(node) { it.parent }.last().boundsInWindow.intersect(native)
}
