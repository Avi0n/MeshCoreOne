package one.meshcore.android

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import one.meshcore.shared.navigation.SEND_ADVERT_SHORTCUT_ID_PREFIX
import one.meshcore.shared.navigation.sendAdvertShortcutSpecs

/**
 * Publishes the Assistant send-advert reach inventory as launcher-hidden dynamic shortcuts bound to
 * `custom.actions.intent.SEND_ADVERT`. Static launcher pins stay absent so a long-press cannot
 * transmit.
 */
class SendAdvertShortcutPublisher(private val context: Context) {
    fun publish(zeroHopLabel: String, floodLabel: String) {
        val shortcuts = sendAdvertShortcutSpecs().map { spec ->
            val label = if (spec.flood) floodLabel else zeroHopLabel
            ShortcutInfoCompat.Builder(context, spec.shortcutId)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_dialog_map))
                .setIntent(
                    Intent(Intent.ACTION_VIEW, spec.url.toUri()).setClassName(
                        context.packageName,
                        MAIN_ACTIVITY,
                    ),
                )
                .addCapabilityBinding(
                    SEND_ADVERT_CAPABILITY,
                    REACH_PARAMETER,
                    listOf(label),
                )
                .setExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER)
                .build()
        }
        val retained = ShortcutManagerCompat.getDynamicShortcuts(context)
            .filter { !it.id.startsWith(SEND_ADVERT_SHORTCUT_ID_PREFIX) }
        ShortcutManagerCompat.setDynamicShortcuts(context, retained + shortcuts)
    }

    fun reportUsed(flood: Boolean) {
        val shortcutId = sendAdvertShortcutSpecs().first { it.flood == flood }.shortcutId
        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).firstOrNull { it.id == shortcutId } ?: return
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    companion object {
        const val SEND_ADVERT_CAPABILITY = "custom.actions.intent.SEND_ADVERT"
        const val REACH_PARAMETER = "reach"
        private const val MAIN_ACTIVITY = "one.meshcore.android.MainActivity"
    }
}
