package one.meshcore.shared.channel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.CreateChannelController
import one.meshcore.shared.contact.ui.QrImage
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_info_copy
import one.meshcore.shared.resources.chats_channel_info_scan_to_join
import one.meshcore.shared.resources.chats_channel_info_secret_key
import one.meshcore.shared.resources.chats_create_private_channel_name
import one.meshcore.shared.resources.chats_create_private_create_button
import one.meshcore.shared.resources.chats_create_private_secret_footer
import one.meshcore.shared.resources.chats_create_private_section_details
import one.meshcore.shared.resources.chats_create_private_section_secret
import one.meshcore.shared.resources.chats_create_private_section_share_manually
import one.meshcore.shared.resources.chats_create_private_share_manually_footer
import one.meshcore.shared.resources.chats_create_private_title_create
import one.meshcore.shared.resources.chats_create_private_title_share
import one.meshcore.shared.resources.localizable_common_ok
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.ButtonIconLabel
import one.meshcore.shared.ui.FormErrorRow
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.PreferenceHubSelectableValueRow
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.preferenceHubInset
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * The create-private-channel screen (the Kotlin analogue of the iOS `CreatePrivateChannelView`): a form that
 * generates the secret, writes the channel, and flips to a share view with the QR + secret. Driven by a
 * [CreateChannelController]; a [ChannelFlowProviders.encode]-backed [QrImage] renders the share QR.
 */
@Composable
fun CreateChannelScreen(
    providers: ChannelFlowProviders,
    availableSlots: List<Int>,
    onBack: () -> Unit,
    onComplete: (ChannelDto?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val latestProviders = rememberUpdatedState(providers)
    val latestAvailableSlots = rememberUpdatedState(availableSlots)
    val controller = remember {
        CreateChannelController(
            CreateChannelController.Dependencies(
                channelService = { latestProviders.value.channelService() },
                radioId = { latestProviders.value.radioId() },
                availableSlots = { latestAvailableSlots.value },
            ),
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val title = if (state.isCreated) {
        stringResource(Res.string.chats_create_private_title_share)
    } else {
        stringResource(Res.string.chats_create_private_title_create)
    }

    ChannelFlowScaffold(
        title = title,
        onBack = onBack,
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier.preferenceHubInset(padding),
            verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = preferenceHubFormScroll(),
                verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!state.isCreated) {
                    PreferenceHubSection(title = stringResource(Res.string.chats_create_private_section_details)) {
                        AppOutlinedTextField(
                            value = state.name,
                            onValueChange = controller::setName,
                            label = { Text(stringResource(Res.string.chats_create_private_channel_name)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(PreferenceHubCardPadding),
                        )
                    }
                    PreferenceHubSection(title = stringResource(Res.string.chats_create_private_section_secret)) {
                        PreferenceHubSelectableValueRow(
                            stringResource(Res.string.chats_channel_info_secret_key),
                            state.secret.toUppercaseHex(),
                        )
                    }
                    PreferenceHubFooter(stringResource(Res.string.chats_create_private_secret_footer))
                    state.error?.let {
                        FormErrorRow(it.message(), Modifier.padding(horizontal = PreferenceHubHorizontalPadding))
                    }
                } else {
                    QrImage(payload = controller.shareUri(), encode = providers.encode)
                    Text(state.name, style = MaterialTheme.typography.headlineSmall)
                    CenteredCaption(stringResource(Res.string.chats_channel_info_scan_to_join))
                    PreferenceHubSection(
                        title = stringResource(Res.string.chats_create_private_section_share_manually),
                    ) {
                        ShareSecretSection(
                            secretHex = state.secret.toUppercaseHex(),
                            onCopy = providers.onCopy,
                        )
                    }
                    PreferenceHubFooter(stringResource(Res.string.chats_create_private_share_manually_footer))
                }
            }
            if (!state.isCreated) {
                Button(
                    onClick = { scope.launch { controller.create() } },
                    enabled = state.canCreate,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PreferenceHubHorizontalPadding),
                ) {
                    if (state.isCreating) {
                        Spinner()
                    } else {
                        Text(stringResource(Res.string.chats_create_private_create_button))
                    }
                }
            } else {
                Button(
                    onClick = { onComplete(state.createdChannel) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PreferenceHubHorizontalPadding),
                ) {
                    Text(stringResource(Res.string.localizable_common_ok))
                }
            }
        }
    }
}

@Composable
internal fun ShareSecretSection(secretHex: String, onCopy: (String) -> Unit) {
    val haptics = rememberAppHaptics()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PreferenceHubSelectableValueRow(
            stringResource(Res.string.chats_channel_info_secret_key),
            secretHex,
        )
        Button(
            onClick = {
                onCopy(secretHex)
                haptics.success()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = PreferenceHubCardPadding,
                    end = PreferenceHubCardPadding,
                    bottom = PreferenceHubCardPadding,
                ),
        ) {
            ButtonIconLabel(
                title = stringResource(Res.string.chats_channel_info_copy),
                icon = AppIcons.ContentCopy,
            )
        }
    }
}
