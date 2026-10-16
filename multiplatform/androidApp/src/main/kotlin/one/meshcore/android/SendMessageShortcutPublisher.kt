package one.meshcore.android

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import one.meshcore.shared.navigation.SEND_MESSAGE_SHORTCUT_ID_PREFIX
import one.meshcore.shared.navigation.SendMessageShortcutSpec

/**
 * Publishes the Assistant send-target inventory as launcher-hidden dynamic shortcuts bound to
 * `actions.intent.SEND_MESSAGE`. Static launcher pins stay in `shortcuts.xml`.
 */
class SendMessageShortcutPublisher(private val context: Context) {
    fun publish(specs: List<SendMessageShortcutSpec>) {
        val shortcuts = specs.map { spec ->
            ShortcutInfoCompat.Builder(context, spec.shortcutId)
                .setShortLabel(spec.displayName)
                .setLongLabel(spec.displayName)
                .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_dialog_email))
                .setIntent(
                    Intent(Intent.ACTION_VIEW, spec.url.toUri()).setClassName(
                        context.packageName,
                        MAIN_ACTIVITY,
                    ),
                )
                .addCapabilityBinding(
                    SEND_MESSAGE_CAPABILITY,
                    RECIPIENT_NAME_PARAMETER,
                    listOf(spec.displayName),
                )
                .setExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER)
                .build()
        }
        val retained = ShortcutManagerCompat.getDynamicShortcuts(context)
            .filter { !it.id.startsWith(SEND_MESSAGE_SHORTCUT_ID_PREFIX) }
        ShortcutManagerCompat.setDynamicShortcuts(context, retained + shortcuts)
    }

    fun reportUsed(targetId: String) {
        val shortcutId = SEND_MESSAGE_SHORTCUT_ID_PREFIX + targetId
        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).firstOrNull { it.id == shortcutId } ?: return
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    companion object {
        const val SEND_MESSAGE_CAPABILITY = "actions.intent.SEND_MESSAGE"
        const val RECIPIENT_NAME_PARAMETER = "message.recipient.name"
        private const val MAIN_ACTIVITY = "one.meshcore.android.MainActivity"
    }
}

/**
 * Fills `text=` on a `meshcore://send` URL from the Assistant extras when the shortcut inventory
 * only carried the recipient.
 */
fun mergeSendMessageText(dataString: String?, extraText: String?): String? {
    if (dataString == null) return null
    if (!dataString.startsWith("meshcore://send")) return dataString
    if (dataString.contains("?text=") || dataString.contains("&text=")) return dataString
    if (extraText.isNullOrEmpty()) return dataString
    val separator = if ('?' in dataString) '&' else '?'
    return buildString {
        append(dataString)
        append(separator)
        append("text=")
        append(encodeQueryValue(extraText))
    }
}

private fun encodeQueryValue(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and QUERY_BYTE_MASK
        val ascii = unsigned.toChar()
        when {
            ascii.isLetterOrDigit() || ascii == '-' || ascii == '_' || ascii == '.' || ascii == '~' -> append(ascii)
            unsigned == QUERY_SPACE_BYTE -> append("%20")
            else -> append('%').append(unsigned.toString(QUERY_HEX_RADIX).padStart(QUERY_HEX_PAD, '0').uppercase())
        }
    }
}

private const val QUERY_BYTE_MASK = 0xFF
private const val QUERY_SPACE_BYTE = 0x20
private const val QUERY_HEX_RADIX = 16
private const val QUERY_HEX_PAD = 2
