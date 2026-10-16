package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.pathdetail.BlockSenderViewModel
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_block_sender_no_matches
import one.meshcore.shared.resources.chats_block_sender_block_anyway
import one.meshcore.shared.resources.chats_block_sender_limitation
import one.meshcore.shared.resources.chats_block_sender_matching_contacts
import one.meshcore.shared.resources.chats_block_sender_title
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Block channel sender form over [BlockSenderViewModel]: name-only limitation copy, optional matching
 * contacts to also block, and confirm. Dismisses when [BlockSenderViewModel] marks the block complete.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun BlockSenderSheet(
    senderName: String,
    radioID: Uuid,
    viewModel: BlockSenderViewModel,
    serviceOwner: ContactService?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    userLocation: UserLocation? = null,
    onBlocked: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // One timestamp anchor for the candidates' last-advert labels; live ticking is not needed here.
    val nowSeconds = remember { Clock.System.now().epochSeconds }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel, serviceOwner, senderName, radioID) {
        viewModel.load(senderName, radioID)
    }
    LaunchedEffect(state.blockCompleted) {
        if (state.blockCompleted) {
            // The open channel reloads after that sender's messages are deleted.
            onBlocked()
            onDismiss()
        }
    }

    FullScreenOverlay(
        onDismiss = onDismiss,
        title = formattedStringResource(Res.string.chats_block_sender_title, senderName),
        modifier = modifier,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = PreferenceHubHorizontalPadding)
                .padding(top = PreferenceHubTopPadding, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(Res.string.chats_block_sender_limitation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.matchingContacts.isNotEmpty() -> {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            PreferenceHubSection(
                                title = stringResource(Res.string.chats_block_sender_matching_contacts),
                                applyHorizontalPadding = false,
                            ) {
                                Column {
                                    for (contact in state.matchingContacts) {
                                        ContactMatchRow(
                                            contact = contact,
                                            selection = ContactMatchSelection.Toggle(
                                                contact.id in state.selectedContactIDs,
                                            ),
                                            userLocation = userLocation,
                                            nowSeconds = nowSeconds,
                                            onClick = { viewModel.toggleContact(contact.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(Res.string.android_block_sender_no_matches),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
            Button(
                onClick = { scope.launch { viewModel.blockAnyway(senderName, radioID) } },
                enabled = !state.isBlocking && !state.isLoading,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                if (state.isBlocking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(Res.string.chats_block_sender_block_anyway))
                }
            }
        }
    }
}
