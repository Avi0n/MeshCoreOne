package one.meshcore.shared.chat.ui

/**
 * Pure lift resolution for the compose bar, matching iOS `ChatKeyboardLift`.
 *
 * Interactive pop / predictive back posts an IME hide while the keyboard window can still be
 * on screen; keep the current lift. Back-button and keyboard drag-to-dismiss still apply
 * [proposed].
 */
object ChatKeyboardLift {
    fun resolvedLift(current: Float, proposed: Float, isInteractivePopActive: Boolean): Float {
        if (isInteractivePopActive && proposed < current) return current
        return proposed
    }

    /** Native layout refreshes a retained window pan after Compose places the new focused bounds. */
    internal class LayoutNotifications(initialLift: Int, private val requestLayout: () -> Unit) {
        private var notifiedLift = initialLift

        fun onPlaced(lift: Int) {
            if (lift == notifiedLift) return
            notifiedLift = lift
            requestLayout()
        }
    }
}

/** The IME already spans the system-navigation area, so reserve that inset only while it is closed. */
internal fun navigationInsetOutsideIme(imeBottom: Int, navigationBottom: Int): Int =
    if (imeBottom > 0) 0 else navigationBottom

/** The content already ends above the navigation bar; the IME inset includes that same area. */
internal fun imeLiftInsideNavigationBounds(imeBottom: Int, navigationBottom: Int): Int =
    (imeBottom - navigationBottom).coerceAtLeast(0)
