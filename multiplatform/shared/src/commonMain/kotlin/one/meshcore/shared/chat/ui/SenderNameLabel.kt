package one.meshcore.shared.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.contact.ui.toComposeColor
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_common_ok
import one.meshcore.shared.resources.chats_message_sender_possible_match
import one.meshcore.shared.resources.chats_message_sender_unverified_nickname
import one.meshcore.shared.resources.chats_message_sender_unverified_nickname_accessibility_label
import one.meshcore.shared.resources.chats_message_sender_unverified_nickname_explanation
import one.meshcore.shared.resources.chats_message_sender_unverified_nickname_format
import one.meshcore.shared.resources.chats_path_hop_possible_match_explanation
import one.meshcore.shared.resources.chats_path_hop_possible_match_title
import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The sender line of an incoming channel bubble. When the wire name matched a local contact by name only,
 * the nickname leads in bold with the unverified wire name parenthesized after it; when the name came from
 * a short public-key prefix shared by several contacts, the name carries a possible-match marker. Both
 * markers open a short explanation, because a channel sender is never cryptographically verified and the
 * user has to be able to tell an asserted identity from a guessed one. Port of the Swift `SenderNameLabel`
 * plus `FallbackMatchIndicatorView`.
 */
@Composable
fun SenderNameLabel(
    resolution: NodeNameResolution,
    modifier: Modifier = Modifier,
    nameColor: Color = MaterialTheme.colorScheme.primary,
) {
    val nickname = resolution.unverifiedNickname
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SENDER_LABEL_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = nickname ?: resolution.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = nameColor,
        )
        when (senderLabelTrust(resolution)) {
            SenderLabelTrust.PLAIN -> Unit

            SenderLabelTrust.UNVERIFIED_NICKNAME -> {
                Text(
                    text = formattedStringResource(
                        Res.string.chats_message_sender_unverified_nickname_format,
                        resolution.displayName,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SenderTrustMarker(
                    accessibilityLabel = stringResource(
                        Res.string.chats_message_sender_unverified_nickname_accessibility_label,
                    ),
                    title = stringResource(Res.string.chats_message_sender_unverified_nickname),
                    explanation = stringResource(Res.string.chats_message_sender_unverified_nickname_explanation),
                )
            }

            SenderLabelTrust.POSSIBLE_MATCH -> SenderTrustMarker(
                accessibilityLabel = stringResource(Res.string.chats_message_sender_possible_match),
                title = stringResource(Res.string.chats_path_hop_possible_match_title),
                explanation = stringResource(Res.string.chats_path_hop_possible_match_explanation),
            )
        }
    }
}

/**
 * How much the sender line has to qualify the name it shows: nothing for a name that came straight off the
 * wire or from a unique key match, the parenthesized wire name plus a marker when a nickname was asserted
 * from a name-only match, and a marker alone when a short key prefix had several candidates.
 */
enum class SenderLabelTrust { PLAIN, UNVERIFIED_NICKNAME, POSSIBLE_MATCH }

fun senderLabelTrust(resolution: NodeNameResolution): SenderLabelTrust = when {
    resolution.unverifiedNickname != null -> SenderLabelTrust.UNVERIFIED_NICKNAME
    resolution.matchKind == NodeNameMatchKind.FALLBACK -> SenderLabelTrust.POSSIBLE_MATCH
    else -> SenderLabelTrust.PLAIN
}

/**
 * Compact Info beside a sender name. A 48dp IconButton would outgrow the bubble's label line.
 * Tapping opens the explanation.
 */
@Composable
private fun SenderTrustMarker(accessibilityLabel: String, title: String, explanation: String) {
    var isShowingExplanation by remember { mutableStateOf(false) }
    Icon(
        imageVector = AppIcons.Info,
        contentDescription = accessibilityLabel,
        tint = MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .size(SENDER_TRUST_MARKER_DP.dp)
            .clickable { isShowingExplanation = true },
    )
    if (isShowingExplanation) {
        AcknowledgedAlertDialog(
            title = title,
            message = explanation,
            confirmLabel = stringResource(Res.string.chats_common_ok),
            onDismiss = { isShowingExplanation = false },
        )
    }
}

/**
 * Incoming channel sender fill from the selected theme's identity gamut, matching iOS
 * `Theme.identityColor(forName:)` used by `UnifiedMessageBubble.senderColor`.
 */
internal fun senderIdentityColor(name: String, theme: AppTheme, useDark: Boolean, highContrast: Boolean): Color =
    theme.identityGamut.colorForName(
        name = name,
        backgroundLuminances = theme.identitySurfaceLuminances(useDark),
        highContrast = highContrast,
    ).toComposeColor()

private const val SENDER_TRUST_MARKER_DP = 14
private const val SENDER_LABEL_SPACING_DP = 4
