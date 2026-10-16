package one.meshcore.shared.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable

@Composable
actual fun rememberReduceMotion(): Boolean = rememberAccessibilitySetting(
    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
    read = ::isReduceMotionEnabled,
)

internal fun isReduceMotionEnabled(context: Context): Boolean {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return isReduceMotionEnabled(animatorDurationScale = scale)
}

internal fun isReduceMotionEnabled(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f
