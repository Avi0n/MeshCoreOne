// AndroidOnly: WP-407 Map display settings use the existing MapEntry native preference keys.
package com.meshcoreone.android.app.navigation

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.container.AppSettingsFeature
import com.meshcoreone.android.core.l10n.generated.AppMapStrings as M
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L

@Composable
internal fun AppMapSettings(feature: AppSettingsFeature, onDone: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("shared-map", Context.MODE_PRIVATE) }
    var clustering by remember { mutableStateOf(preferences.getBoolean("cluster-v1", true)) }
    var labels by remember { mutableStateOf(preferences.getBoolean("labels-v1", true)) }
    fun write(key: String, value: Boolean): Boolean = try {
        check(preferences.edit().putBoolean(key, value).commit()) { "Map display setting did not persist" }
        true
    } catch (failure: Exception) { feature.report("Map display setting failed", failure); false }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(S.mapsDisplayHeader), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(M.mapControlsClusterNodes), Modifier.weight(1f))
            Switch(clustering, { if (write("cluster-v1", it)) clustering = it })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(M.mapControlsShowLabels), Modifier.weight(1f))
            Switch(labels, { if (write("labels-v1", it)) labels = it })
        }
        TextButton(onClick = onDone) { Text(stringResource(L.commonDone)) }
    }
}
