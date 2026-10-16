@file:OptIn(ExperimentalMaterial3Api::class)

package one.meshcore.shared.chat.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import one.meshcore.shared.ui.modalSheetExpandedValues
import one.meshcore.shared.ui.modalSheetPartialValues

internal data class MessageActionsSheetChrome(val accessibilitySize: Boolean, val sheetState: SheetState)

/** iOS `dynamicTypeSize.isAccessibilitySize` starts at Accessibility 1 (~160%+). */
internal const val ACCESSIBILITY_DYNAMIC_TYPE_FONT_SCALE = 1.6f

internal fun isAccessibilityDynamicType(fontScale: Float): Boolean = fontScale >= ACCESSIBILITY_DYNAMIC_TYPE_FONT_SCALE

/** iOS `.regular` horizontal size class — skip the medium detent on tablets and large landscape phones. */
internal const val MESSAGE_ACTIONS_REGULAR_WIDTH_DP = 600

internal fun shouldSkipMessageActionsPartialExpand(isAccessibilitySize: Boolean, containerWidthDp: Float): Boolean =
    isAccessibilitySize || containerWidthDp >= MESSAGE_ACTIONS_REGULAR_WIDTH_DP

/** Same detent and preview size for a chat message sheet and a room message sheet. */
@Composable
internal fun rememberMessageActionsSheetChrome(): MessageActionsSheetChrome {
    val fontScale = LocalDensity.current.fontScale
    val accessibilitySize = isAccessibilityDynamicType(fontScale)
    val windowWidthDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp().value
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = if (
            shouldSkipMessageActionsPartialExpand(
                isAccessibilitySize = accessibilitySize,
                containerWidthDp = windowWidthDp,
            )
        ) {
            modalSheetExpandedValues
        } else {
            modalSheetPartialValues
        },
    )
    return MessageActionsSheetChrome(accessibilitySize = accessibilitySize, sheetState = sheetState)
}
