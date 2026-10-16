package one.meshcore.shared.whatsnew.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_support_title
import one.meshcore.shared.resources.whatsnew_whats_new_continue_button
import one.meshcore.shared.resources.whatsnew_whats_new_failed_sends_description
import one.meshcore.shared.resources.whatsnew_whats_new_failed_sends_title
import one.meshcore.shared.resources.whatsnew_whats_new_full_release_notes
import one.meshcore.shared.resources.whatsnew_whats_new_message_translation_description
import one.meshcore.shared.resources.whatsnew_whats_new_message_translation_title
import one.meshcore.shared.resources.whatsnew_whats_new_sender_avatars_description
import one.meshcore.shared.resources.whatsnew_whats_new_sender_avatars_title
import one.meshcore.shared.resources.whatsnew_whats_new_title
import one.meshcore.shared.ui.ButtonIconLabel
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.preferenceHubInset
import one.meshcore.shared.whatsnew.WhatsNewItem
import one.meshcore.shared.whatsnew.WhatsNewRelease
import one.meshcore.shared.whatsnew.WhatsNewSymbols
import org.jetbrains.compose.resources.stringResource

@Composable
fun WhatsNewSheet(
    release: WhatsNewRelease,
    onDismiss: () -> Unit,
    onSupport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    FullScreenOverlay(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(Res.string.whatsnew_whats_new_title),
    ) { innerPadding ->
        Column(
            Modifier.preferenceHubInset(innerPadding),
            verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
        ) {
            Column(preferenceHubFormScroll()) {
                PreferenceHubSection {
                    Column(
                        Modifier.padding(PreferenceHubCardPadding),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        release.items.forEach { item ->
                            WhatsNewRow(item = item)
                        }
                    }
                }
            }
            TextButton(
                onClick = { uriHandler.openUri(release.releaseNotesUrl) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = PreferenceHubCardPadding),
            ) {
                Text(stringResource(Res.string.whatsnew_whats_new_full_release_notes))
            }
            OutlinedButton(
                onClick = onSupport,
                modifier = Modifier.fillMaxWidth().padding(horizontal = PreferenceHubCardPadding),
            ) {
                ButtonIconLabel(
                    title = stringResource(Res.string.settings_support_title),
                    icon = AppIcons.Favorite,
                )
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = PreferenceHubCardPadding),
            ) {
                Text(stringResource(Res.string.whatsnew_whats_new_continue_button))
            }
        }
    }
}

private val WhatsNewSymbolColumnWidth = 40.dp
private val WhatsNewSymbolToTextSpacing = 16.dp
private val WhatsNewSymbolSize = 28.dp
private val WhatsNewTitleToBodySpacing = 4.dp

@Composable
private fun WhatsNewRow(item: WhatsNewItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(WhatsNewSymbolToTextSpacing),
    ) {
        Box(
            modifier = Modifier.width(WhatsNewSymbolColumnWidth),
            contentAlignment = Alignment.TopCenter,
        ) {
            Icon(
                imageVector = whatsNewRowIcon(item.symbol),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(WhatsNewSymbolSize),
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(WhatsNewTitleToBodySpacing),
        ) {
            Text(
                text = itemTitle(item),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = itemDescription(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun whatsNewRowIcon(symbol: String): ImageVector = when (symbol) {
    WhatsNewSymbols.TRANSLATE -> AppIcons.Translate
    WhatsNewSymbols.PERSON -> AppIcons.Person
    WhatsNewSymbols.REFRESH -> AppIcons.Refresh
    else -> AppIcons.Info
}

@Composable
private fun itemTitle(item: WhatsNewItem): String = when (item.titleRes) {
    "whatsnew_whats_new_message_translation_title" ->
        stringResource(Res.string.whatsnew_whats_new_message_translation_title)
    "whatsnew_whats_new_sender_avatars_title" ->
        stringResource(Res.string.whatsnew_whats_new_sender_avatars_title)
    "whatsnew_whats_new_failed_sends_title" ->
        stringResource(Res.string.whatsnew_whats_new_failed_sends_title)
    else -> item.titleRes
}

@Composable
private fun itemDescription(item: WhatsNewItem): String = when (item.descriptionRes) {
    "whatsnew_whats_new_message_translation_description" ->
        stringResource(Res.string.whatsnew_whats_new_message_translation_description)
    "whatsnew_whats_new_sender_avatars_description" ->
        stringResource(Res.string.whatsnew_whats_new_sender_avatars_description)
    "whatsnew_whats_new_failed_sends_description" ->
        stringResource(Res.string.whatsnew_whats_new_failed_sends_description)
    else -> item.descriptionRes
}
