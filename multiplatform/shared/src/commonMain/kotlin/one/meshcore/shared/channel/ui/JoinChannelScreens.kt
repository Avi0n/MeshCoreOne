package one.meshcore.shared.channel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.JoinHashtagChannelController
import one.meshcore.shared.channel.JoinPrivateChannelController
import one.meshcore.shared.channel.JoinPublicChannelController
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_create_private_channel_name
import one.meshcore.shared.resources.chats_create_private_section_details
import one.meshcore.shared.resources.chats_join_hashtag_already_joined
import one.meshcore.shared.resources.chats_join_hashtag_already_joined_accessibility
import one.meshcore.shared.resources.chats_join_hashtag_encryption_description
import one.meshcore.shared.resources.chats_join_hashtag_existing_hint
import one.meshcore.shared.resources.chats_join_hashtag_footer
import one.meshcore.shared.resources.chats_join_hashtag_go_to_button
import one.meshcore.shared.resources.chats_join_hashtag_join_button
import one.meshcore.shared.resources.chats_join_hashtag_new_hint
import one.meshcore.shared.resources.chats_join_hashtag_placeholder
import one.meshcore.shared.resources.chats_join_hashtag_section_header
import one.meshcore.shared.resources.chats_join_hashtag_title
import one.meshcore.shared.resources.chats_join_private_error_invalid_secret
import one.meshcore.shared.resources.chats_join_private_footer
import one.meshcore.shared.resources.chats_join_private_join_button
import one.meshcore.shared.resources.chats_join_private_secret_key_placeholder
import one.meshcore.shared.resources.chats_join_private_title
import one.meshcore.shared.resources.chats_join_public_add_button
import one.meshcore.shared.resources.chats_join_public_channel_name
import one.meshcore.shared.resources.chats_join_public_description
import one.meshcore.shared.resources.chats_join_public_title
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.FormErrorRow
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.preferenceHubInset
import org.jetbrains.compose.resources.stringResource

/** The join-private-channel screen: name + hex-secret entry, then a device write. */
@Composable
fun JoinPrivateChannelScreen(
    providers: ChannelFlowProviders,
    availableSlots: List<Int>,
    onBack: () -> Unit,
    onJoined: (ChannelDto?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val latestProviders = rememberUpdatedState(providers)
    val latestAvailableSlots = rememberUpdatedState(availableSlots)
    val controller = remember {
        JoinPrivateChannelController(
            JoinPrivateChannelController.Dependencies(
                { latestProviders.value.channelService() },
                { latestProviders.value.radioId() },
                { latestAvailableSlots.value },
            ),
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()

    ChannelFlowScaffold(stringResource(Res.string.chats_join_private_title), onBack, modifier) { padding ->
        Column(
            modifier = Modifier.preferenceHubInset(padding),
            verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
        ) {
            Column(
                modifier = preferenceHubFormScroll(),
                verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
            ) {
                PreferenceHubSection(title = stringResource(Res.string.chats_create_private_section_details)) {
                    Column(
                        modifier = Modifier.padding(PreferenceHubCardPadding),
                        verticalArrangement = Arrangement.spacedBy(PreferenceHubCardPadding),
                    ) {
                        AppOutlinedTextField(
                            value = state.name,
                            onValueChange = controller::setName,
                            label = { Text(stringResource(Res.string.chats_create_private_channel_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AppOutlinedTextField(
                            value = state.secretHex,
                            onValueChange = controller::setSecretHex,
                            label = { Text(stringResource(Res.string.chats_join_private_secret_key_placeholder)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (state.footerIsError) {
                    FormErrorRow(
                        stringResource(Res.string.chats_join_private_error_invalid_secret),
                        Modifier.padding(horizontal = PreferenceHubHorizontalPadding),
                    )
                } else {
                    PreferenceHubFooter(stringResource(Res.string.chats_join_private_footer))
                }
                state.error?.let {
                    FormErrorRow(it.message(), Modifier.padding(horizontal = PreferenceHubHorizontalPadding))
                }
            }
            Button(
                onClick = { scope.launch { controller.join(onJoined) } },
                enabled = state.canJoin,
                modifier = Modifier.fillMaxWidth().padding(horizontal = PreferenceHubHorizontalPadding),
            ) {
                if (state.isJoining) Spinner() else Text(stringResource(Res.string.chats_join_private_join_button))
            }
        }
    }
}

/** The join-public-channel screen: a single button that re-adds the public channel on slot 0. */
@Composable
fun JoinPublicChannelScreen(
    providers: ChannelFlowProviders,
    onBack: () -> Unit,
    onJoined: (ChannelDto?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val latestProviders = rememberUpdatedState(providers)
    val controller = remember {
        JoinPublicChannelController(
            JoinPublicChannelController.Dependencies(
                channelService = { latestProviders.value.channelService() },
                radioId = { latestProviders.value.radioId() },
            ),
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()

    ChannelFlowScaffold(stringResource(Res.string.chats_join_public_title), onBack, modifier) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = AppIcons.Public,
                    contentDescription = null,
                    modifier = Modifier.size(JOIN_PUBLIC_GLOBE_DP.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(Res.string.chats_join_public_channel_name),
                    style = MaterialTheme.typography.titleLarge,
                )
                CenteredCaption(stringResource(Res.string.chats_join_public_description))
                state.error?.let { FormErrorRow(it.message()) }
            }
            Button(
                onClick = { scope.launch { controller.join(onJoined) } },
                enabled = !state.isJoining,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isJoining) {
                    Spinner()
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                }
                Text(stringResource(Res.string.chats_join_public_add_button))
            }
        }
    }
}

/** The join-hashtag-channel screen: a `#`-prefixed name entry, existing-channel detection, and a device write. */
@Composable
fun JoinHashtagChannelScreen(
    providers: ChannelFlowProviders,
    availableSlots: List<Int>,
    onBack: () -> Unit,
    onJoined: (ChannelDto?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val latestProviders = rememberUpdatedState(providers)
    val latestAvailableSlots = rememberUpdatedState(availableSlots)
    val controller = remember {
        JoinHashtagChannelController(
            JoinHashtagChannelController.Dependencies(
                { latestProviders.value.channelService() },
                { latestProviders.value.radioId() },
                { latestAvailableSlots.value },
            ),
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val channelService = providers.channelService()
    val radioId = providers.radioId()
    LaunchedEffect(controller, channelService, radioId) { controller.loadExisting() }

    ChannelFlowScaffold(stringResource(Res.string.chats_join_hashtag_title), onBack, modifier) { padding ->
        Column(
            modifier = Modifier.preferenceHubInset(padding),
            verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
        ) {
            Column(
                modifier = preferenceHubFormScroll(),
                verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
            ) {
                PreferenceHubSection(title = stringResource(Res.string.chats_join_hashtag_section_header)) {
                    AppOutlinedTextField(
                        value = state.name,
                        onValueChange = controller::setName,
                        label = { Text(stringResource(Res.string.chats_join_hashtag_placeholder)) },
                        leadingIcon = {
                            Text(
                                "#",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PreferenceHubCardPadding),
                    )
                }
                PreferenceHubFooter(stringResource(Res.string.chats_join_hashtag_footer))
                PreferenceHubFooter(stringResource(Res.string.chats_join_hashtag_encryption_description))
                state.error?.let {
                    FormErrorRow(it.message(), Modifier.padding(horizontal = PreferenceHubHorizontalPadding))
                }
                if (state.existingChannel != null) {
                    val alreadyJoinedA11y =
                        stringResource(Res.string.chats_join_hashtag_already_joined_accessibility)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PreferenceHubHorizontalPadding)
                            .semantics { contentDescription = alreadyJoinedA11y },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringResource(Res.string.chats_join_hashtag_already_joined))
                    }
                }
            }
            val joinHint = if (state.existingChannel != null) {
                stringResource(Res.string.chats_join_hashtag_existing_hint)
            } else {
                stringResource(Res.string.chats_join_hashtag_new_hint)
            }
            Button(
                onClick = { scope.launch { controller.join(onJoined) } },
                enabled = state.canJoin,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubHorizontalPadding)
                    .semantics { onClick(label = joinHint, action = null) },
            ) {
                if (state.isJoining && state.existingChannel == null) {
                    Spinner()
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                }
                when {
                    state.existingChannel != null ->
                        Text(formattedStringResource(Res.string.chats_join_hashtag_go_to_button, state.name))
                    else -> Text(formattedStringResource(Res.string.chats_join_hashtag_join_button, state.name))
                }
            }
        }
    }
}

@Composable
internal fun Spinner() = CircularProgressIndicator(
    modifier = Modifier.size(ButtonDefaults.IconSize),
    strokeWidth = 2.dp,
    color = MaterialTheme.colorScheme.onPrimary,
)

/** Material empty-state icon size, matching `PlaceholderPane`. */
internal const val JOIN_PUBLIC_GLOBE_DP = 48
