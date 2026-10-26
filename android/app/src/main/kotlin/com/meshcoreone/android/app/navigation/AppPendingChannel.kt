// AndroidOnly: WP-407 Channel/hashtag links stage a user confirmation; they never write a radio during composition.
package com.meshcoreone.android.app.navigation

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.meshcoreone.android.app.container.AppChatsFeature
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.feature.chats.channels.ChannelLink
import com.meshcoreone.android.feature.chats.channels.ChannelSheetsStateHolder
import kotlinx.coroutines.launch

@Composable
internal fun AppPendingChannel(feature: AppChatsFeature, navigation: NavigationCoordinator) {
    val pending by navigation.state.collectAsState()
    val link = pending.pendingChannelLink
    val hashtag = pending.pendingHashtag
    if (link == null && hashtag == null) return
    val radio by feature.currentRadioId.collectAsState()
    val holder = remember(feature, radio, link, hashtag) {
        ChannelSheetsStateHolder(radio, feature.maximumChannels(), feature.channels)
    }
    val state by holder.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clear = {
        if (navigation.state.value.pendingChannelLink == link) navigation.clearPendingChannelLink()
        if (navigation.state.value.pendingHashtag == hashtag) navigation.clearPendingHashtag()
    }
    LaunchedEffect(holder) { holder.load() }
    AlertDialog(onDismissRequest = clear,
        text = {
            Column {
                Text(link?.name ?: requireNotNull(hashtag).id)
                link?.regionScope?.let {
                    Text(stringResource(com.meshcoreone.android.core.l10n.R.string.l10n_app_chats_chats_joinfrommessage_regionscope, it))
                }
                if (link?.hasHashtagSecretMismatch == true) {
                    Text(stringResource(com.meshcoreone.android.core.l10n.R.string.l10n_app_chats_chats_joinfrommessage_hashtagsecretmismatch))
                }
                state.failure?.let { Text(com.meshcoreone.android.core.ui.uiString(com.meshcoreone.android.core.ui.UiErrorMapper().message(it))) }
            }
        },
        confirmButton = {
            val connection by feature.connectionState.collectAsState()
            TextButton(enabled = radio != null && connection == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY &&
                !state.isLoading && !state.isSubmitting, onClick = {
                scope.launch {
                    val channel = if (link != null) holder.joinLink(ChannelLink(
                        link.name, link.secret, link.regionScope, link.hasHashtagSecretMismatch,
                    ))
                    else {
                        holder.updateHashtag(requireNotNull(hashtag).id)
                        holder.joinHashtag()
                    }
                    channel?.let { clear(); navigation.navigateToChannel(it) }
                }
            }) { Text(stringResource(L.commonOk)) }
        },
        dismissButton = { TextButton(onClick = clear) { Text(stringResource(L.commonCancel)) } },
    )
}
