package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_ok
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_possible_match
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_possible_match_explanation
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_possible_match_title
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Marks a node name that was resolved from a short public-key prefix several nodes share, so the name
 * shown may belong to the wrong node.
 *
 * Tappable on purpose: the marker alone says a name is uncertain but not why, and a reader deciding which
 * repeater dropped needs the reason. Mirrors the iOS `FallbackMatchIndicatorView` popover.
 */
@Composable
fun PossibleMatchMarker(modifier: Modifier = Modifier) {
    var showingExplanation by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.remotenodes_remote_nodes_status_possible_match)
    val hint = stringResource(possibleMatchMarkerHintRes())

    IconButton(
        onClick = { showingExplanation = true },
        modifier = modifier.semantics { stateDescription = hint },
    ) {
        Icon(
            imageVector = POSSIBLE_MATCH_ICON,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(POSSIBLE_MATCH_ICON_DP.dp),
        )
    }

    if (showingExplanation) {
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.remotenodes_remote_nodes_status_possible_match_title),
            message = stringResource(Res.string.remotenodes_remote_nodes_status_possible_match_explanation),
            confirmLabel = stringResource(Res.string.contacts_common_ok),
            onDismiss = { showingExplanation = false },
        )
    }
}

/** iOS `FallbackMatchIndicatorView` VoiceOver hint — why the name may be the wrong node. */
internal fun possibleMatchMarkerHintRes() = Res.string.remotenodes_remote_nodes_status_possible_match_explanation

internal val POSSIBLE_MATCH_ICON get() = AppIcons.Info

/** Default IconButton size so the marker stays a 48dp target. */
internal const val POSSIBLE_MATCH_BUTTON_DP = 48
internal const val POSSIBLE_MATCH_ICON_DP = 16
