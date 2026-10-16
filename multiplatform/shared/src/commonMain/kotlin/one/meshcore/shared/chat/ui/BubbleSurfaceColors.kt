package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_common_ok
import one.meshcore.shared.resources.chats_message_action_translating
import one.meshcore.shared.resources.chats_message_region_accessibility_label
import one.meshcore.shared.resources.chats_message_region_ambiguous_accessibility_label
import one.meshcore.shared.resources.chats_message_region_ambiguous_popover_body
import one.meshcore.shared.resources.chats_message_region_ambiguous_popover_title
import one.meshcore.shared.resources.chats_message_translated_accessibility
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

/** Container and content colors for one [BubbleFillRole]. */
internal data class BubbleSurfaceColors(val container: Color, val content: Color)

@Composable
internal fun bubbleSurfaceColors(fill: BubbleFillRole): BubbleSurfaceColors {
    val scheme = MaterialTheme.colorScheme
    return when (fill) {
        BubbleFillRole.FAILED -> BubbleSurfaceColors(scheme.error, scheme.onError)
        BubbleFillRole.OUTGOING -> BubbleSurfaceColors(scheme.primary, scheme.onPrimary)
        BubbleFillRole.INCOMING -> BubbleSurfaceColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

@Composable
internal fun spokenTranslationBody(
    kind: TranslationSpokenKind,
    translatedText: String,
    originalBody: String,
): String? = when (kind) {
    TranslationSpokenKind.TRANSLATED ->
        formattedStringResource(Res.string.chats_message_translated_accessibility, translatedText)
    TranslationSpokenKind.IN_PROGRESS ->
        "$originalBody, ${stringResource(Res.string.chats_message_action_translating)}"
    TranslationSpokenKind.ORIGINAL -> null
}

@Composable
internal fun regionSpokenText(region: String?, matchNames: List<String>): String? =
    when (regionSpokenKind(region, matchNames.size)) {
        RegionSpokenKind.NONE -> null
        RegionSpokenKind.AMBIGUOUS -> formattedStringResource(
            Res.string.chats_message_region_ambiguous_accessibility_label,
            matchNames.joinToString(", "),
        )
        RegionSpokenKind.NAMED -> formattedStringResource(
            Res.string.chats_message_region_accessibility_label,
            region.orEmpty(),
        )
    }

@Composable
internal fun RegionAmbiguousDialog(visible: Boolean, matchNames: List<String>, onDismiss: () -> Unit) {
    if (visible) {
        val listBody = "\n" + matchNames.joinToString("\n")
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.chats_message_region_ambiguous_popover_title),
            message = formattedStringResource(Res.string.chats_message_region_ambiguous_popover_body, listBody),
            confirmLabel = stringResource(Res.string.chats_common_ok),
            onDismiss = onDismiss,
        )
    }
}
