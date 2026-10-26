// PortedFrom: MC1/Views/PathEditing/AddHopPickerView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.feature.nodes.path.AddHopFilter
import com.meshcoreone.android.feature.nodes.path.AddHopIntent
import com.meshcoreone.android.feature.nodes.path.AddHopPicker
import com.meshcoreone.android.feature.nodes.path.PathManagementStateHolder

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddHopPickerDialog(holder: PathManagementStateHolder, onDismiss: () -> Unit) {
    val state by holder.state.collectAsStateWithLifecycle()
    val sessionRecentKeys = remember(holder) { holder.recentPublicKeys.toList() }
    var filter by remember { mutableStateOf(AddHopFilter.ALL) }
    var query by remember { mutableStateOf("") }
    val locale = LocalConfiguration.current.locales[0]
    val results = remember(state, filter, query, locale) {
        AddHopPicker.buildResults(holder, filter, sessionRecentKeys, query, locale)
    }
    val sections = listOf(
        R.string.l10n_app_contacts_contacts_pathedit_sections_recent to results.recent,
        R.string.l10n_app_contacts_contacts_pathedit_sections_favorites to results.favorites,
        R.string.l10n_app_contacts_contacts_pathedit_sections_contacts to results.contacts,
        R.string.l10n_app_contacts_contacts_pathedit_sections_discovered to results.discovered,
        R.string.l10n_app_contacts_contacts_pathedit_sections_rooms to results.rooms,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(C.contactsCommonDone)) } },
        title = { Text(stringResource(R.string.l10n_app_contacts_contacts_pathedit_addhop)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    Text(nodesString(AddHopPicker.bannerText(holder, AddHopIntent.APPEND)))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(C.contactsPathEditSearchPrompt)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AddHopFilter.entries.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(stringResource(option.labelRes)) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
                if (results.isEmpty) {
                    item { Text(stringResource(AddHopPicker.emptyStateTitle(filter))) }
                }
                sections.forEach { (title, nodes) ->
                    if (nodes.isNotEmpty()) {
                        item(key = title) {
                            Text(
                                stringResource(title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        items(nodes, key = { "$title:${it.underlying.publicKey.hexString}" }) { node ->
                            TextButton(
                                onClick = {
                                    holder.appendHop(node.underlying)
                                    if (holder.isPathFull) onDismiss()
                                },
                                enabled = !holder.isPathFull,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(node.displayName)
                                    Text(node.publicKeyHex, style = MaterialTheme.typography.bodySmall)
                                    if (node.isFavorite) Text(stringResource(AddHopFilter.FAVORITES.labelRes))
                                    if (node.isDiscovered) Text(stringResource(AddHopFilter.DISCOVERED.labelRes))
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
