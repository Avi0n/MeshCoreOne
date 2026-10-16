package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.BlockedChannelSenderService
import one.meshcore.shared.contact.BlockedChannelSendersViewModel
import one.meshcore.shared.contact.relativeAgeLabel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_blocking_channel_senders_empty_description
import one.meshcore.shared.resources.settings_blocking_channel_senders_empty_title
import one.meshcore.shared.resources.settings_blocking_channel_senders_title
import one.meshcore.shared.resources.settings_blocking_channel_senders_unblock
import one.meshcore.shared.ui.OptionalNestedScaffold
import one.meshcore.shared.ui.expressiveListItem
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Settings → Blocking → Channel Senders: the blocked-name list with per-row unblock (iOS
 * `BlockedChannelSendersView`). Names are not contacts — they are heard-on-a-channel display names.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun BlockedChannelSendersScreen(
    service: () -> BlockedChannelSenderService?,
    radioId: () -> Uuid?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** False when a parent [Scaffold] already titled this destination (Settings hub). */
    showAppBar: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val currentService = service()
    val currentRadioId = radioId()
    val viewModel = rememberBlockedChannelSendersViewModel(service, radioId)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowSeconds = remember { Clock.System.now().epochSeconds }

    LaunchedEffect(viewModel, currentService, currentRadioId) { viewModel.load() }

    val body: @Composable (PaddingValues) -> Unit = { innerPadding ->
        val current = state.senders
        when {
            !state.hasLoadedOnce -> {
                Box(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            current.isEmpty() -> {
                BlockedEmptyPane(
                    title = stringResource(Res.string.settings_blocking_channel_senders_empty_title),
                    description = stringResource(Res.string.settings_blocking_channel_senders_empty_description),
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                )
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                    items(current, key = { it.id.value }) { sender ->
                        val unblockLabel = stringResource(Res.string.settings_blocking_channel_senders_unblock)
                        ListItem(
                            headlineContent = { Text(sender.name) },
                            supportingContent = {
                                Text(relativeAgeLabel(sender.dateBlocked.toLong(), nowSeconds))
                            },
                            trailingContent = {
                                AppIconButton(
                                    imageVector = AppIcons.DoNotTouch,
                                    contentDescription = unblockLabel,
                                    onClick = {
                                        scope.launch { viewModel.unblock(sender.name) }
                                    },
                                )
                            },
                            modifier = expressiveListItem(Modifier.fillMaxWidth()),
                        )
                    }
                }
            }
        }
    }
    OptionalNestedScaffold(
        showAppBar = showAppBar,
        title = stringResource(Res.string.settings_blocking_channel_senders_title),
        onBack = onBack,
        modifier = modifier,
        content = body,
    )
}

@Composable
internal fun rememberBlockedChannelSendersViewModel(
    service: () -> BlockedChannelSenderService?,
    radioId: () -> Uuid?,
): BlockedChannelSendersViewModel {
    val currentService by rememberUpdatedState(service)
    val currentRadioId by rememberUpdatedState(radioId)
    return remember {
        BlockedChannelSendersViewModel(
            BlockedChannelSendersViewModel.Dependencies(
                service = { currentService() },
                radioId = { currentRadioId() },
            ),
        )
    }
}
