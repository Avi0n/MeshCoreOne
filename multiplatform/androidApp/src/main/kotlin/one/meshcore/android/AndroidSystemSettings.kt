package one.meshcore.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.net.toUri

/** System settings screens and the process-text translation handoff. */
internal interface AndroidSystemSettings {
    fun openAppSettings()

    /**
     * Opens the system per-app language picker (Settings → Language row). Uses the dedicated locale
     * screen on Android 13+ and falls back to the app details page on older releases, which have no
     * per-app locale surface.
     */
    fun openAppLanguageSettings()

    fun openBluetoothSettings()

    /**
     * Opens the exemption prompt, or the system list when this app is already exempt. Mesh messages arrive through the
     * paired radio, so FCM cannot replace the persistent connection that Doze would interrupt.
     */
    fun openBatteryOptimizationSettings()

    /**
     * Hands [text] to a system PROCESS_TEXT handler (Translate, etc.). iOS
     * `.translationPresentation`. Falls back to the Translate website when no handler is installed.
     */
    fun presentSystemTranslation(text: String)

    fun openSystemNotificationSettings()
}

internal class AndroidSystemSettingsLinks(private val activity: ComponentActivity) : AndroidSystemSettings {
    override fun openAppSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch(intent)
    }

    override fun openAppLanguageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val pkg = Uri.fromParts("package", activity.packageName, null)
            val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch(intent)) return
        }
        openAppSettings()
    }

    override fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch(intent)
    }

    @SuppressLint("BatteryLife")
    override fun openBatteryOptimizationSettings() {
        val pkg = activity.packageName
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(pkg)) {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData("package:$pkg".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch(request)) return
        }
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch(list)) return
        openAppSettings()
    }

    override fun presentSystemTranslation(text: String) {
        if (text.isBlank()) return
        val processText = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        if (launch(Intent.createChooser(processText, null))) return
        val encoded = Uri.encode(text.take(TRANSLATE_WEB_MAX_CHARS))
        val web = Intent(Intent.ACTION_VIEW, "$TRANSLATE_WEB_URL$encoded".toUri())
        launch(web)
    }

    override fun openSystemNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch(intent)
    }

    private fun launch(intent: Intent): Boolean = runCatching { activity.startActivity(intent) }.isSuccess

    private companion object {
        const val TRANSLATE_WEB_URL = "https://translate.google.com/?sl=auto&text="
        const val TRANSLATE_WEB_MAX_CHARS = 4_500
    }
}
