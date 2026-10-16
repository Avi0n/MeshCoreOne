package one.meshcore.shared.layout

/**
 * Height of phone-style picker sheets. Compact windows keep the iOS-large detent fraction; Material
 * medium and wider cap the sheet so a 640 dp-wide modal is not 90% of a tablet window.
 */
object PickerSheetLayout {
    /** Fraction of window height on medium+ windows (Material dialog-like, not a phone detent). */
    const val MEDIUM_HEIGHT_FRACTION = 0.55f

    fun heightFraction(containerWidthDp: Float, compactFraction: Float): Float =
        if (SidebarLayout.isMaterialMedium(containerWidthDp)) {
            MEDIUM_HEIGHT_FRACTION
        } else {
            compactFraction
        }
}
