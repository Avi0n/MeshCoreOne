package one.meshcore.shared.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable

/**
 * Secure setting key for Android's "High contrast text" accessibility toggle.
 * Not exposed as a typed constant on all SDK levels; string form is stable.
 */
internal const val HIGH_TEXT_CONTRAST_ENABLED = "high_text_contrast_enabled"

/** Contrast preference at or above this fraction counts as increased contrast (Android 14+). */
internal const val CONTRAST_THRESHOLD = 0.34f

@Composable
actual fun rememberHighContrast(): Boolean = rememberAccessibilitySetting(
    Settings.Secure.getUriFor(HIGH_TEXT_CONTRAST_ENABLED),
    extraSubscribe = ::subscribeSystemContrastChanges,
    read = ::isHighContrastEnabled,
)

internal fun subscribeSystemContrastChanges(context: Context, refresh: () -> Unit): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return {}
    val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return {}
    val listener = UiModeManager.ContrastChangeListener { refresh() }
    uiMode.addContrastChangeListener(context.mainExecutor, listener)
    return { uiMode.removeContrastChangeListener(listener) }
}

internal fun isHighContrastEnabled(context: Context): Boolean {
    val highText = Settings.Secure.getInt(
        context.contentResolver,
        HIGH_TEXT_CONTRAST_ENABLED,
        0,
    ) == 1
    val systemContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        uiMode?.contrast ?: 0f
    } else {
        0f
    }
    return isHighContrastEnabled(highTextContrast = highText, systemContrast = systemContrast)
}

internal fun isHighContrastEnabled(highTextContrast: Boolean, systemContrast: Float): Boolean =
    highTextContrast || systemContrast >= CONTRAST_THRESHOLD
