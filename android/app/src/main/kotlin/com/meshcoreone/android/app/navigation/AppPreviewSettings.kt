// PortedFrom: MC1/Views/Settings/Sections/LinkPreviewSettingsSection.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.container.AppSettingsFeature
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceKey
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L

@Composable
internal fun AppPreviewSettings(feature: AppSettingsFeature, onDone: () -> Unit) {
    val preferences by feature.preferenceValues.collectAsState()
    val enabled = preferences[AppStorageKey.linkPreviewsEnabled]
    AlertDialog(onDismissRequest = onDone,
        title = { Text(stringResource(S.linkPreviewsHeader)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf(
                    S.linkPreviewsToggle to AppStorageKey.linkPreviewsEnabled,
                    S.linkPreviewsShowInDMs to AppStorageKey.linkPreviewsAutoResolveDM,
                    S.linkPreviewsShowInChannels to AppStorageKey.linkPreviewsAutoResolveChannels,
                    S.inlineImagesAutoPlayGifs to AppStorageKey.autoPlayGIFs,
                )
                options.forEach { (label, key) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(label), Modifier.weight(1f))
                        Switch(preferences[key], { feature.setPreference(key, it) },
                            enabled = key == AppStorageKey.linkPreviewsEnabled || enabled)
                    }
                }
                Text(stringResource(S.linkPreviewsFooter))
                Text(stringResource(S.linkPreviewsReduceMotionNote))
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(L.commonDone)) } },
    )
}
