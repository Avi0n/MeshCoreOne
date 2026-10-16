package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_discovery_add
import one.meshcore.shared.resources.contacts_discovery_added
import one.meshcore.shared.resources.contacts_discovery_added_accessibility
import org.jetbrains.compose.resources.stringResource

/** VoiceOver speaks "Already added to contacts", not the visible "Added" label. */
internal fun discoveryAddedSpokenRes() = Res.string.contacts_discovery_added_accessibility

/**
 * Trailing add state shared by contact discovery and node discovery.
 * The Add button is absent while a row is already added or still adding.
 */
@Composable
internal fun DiscoveryAddTrailing(isAdded: Boolean, isAdding: Boolean, onAdd: (() -> Unit)?) {
    when {
        isAdded -> {
            val addedSpoken = stringResource(discoveryAddedSpokenRes())
            Text(
                text = stringResource(Res.string.contacts_discovery_added),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = addedSpoken },
            )
        }
        isAdding -> CircularProgressIndicator(modifier = Modifier.size(DISCOVERY_ADDING_INDICATOR))
        onAdd != null -> FilledTonalButton(onClick = onAdd) {
            Text(stringResource(Res.string.contacts_discovery_add))
        }
    }
}

private val DISCOVERY_ADDING_INDICATOR = 20.dp
