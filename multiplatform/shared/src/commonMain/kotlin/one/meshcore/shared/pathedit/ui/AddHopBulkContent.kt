package one.meshcore.shared.pathedit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.pathedit.HopPickerSource
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_path_edit_bulk_add_action
import one.meshcore.shared.resources.contacts_path_edit_bulk_add_empty
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BulkAddContent(source: HopPickerSource, input: String, onAdded: () -> Unit, onHopsAdded: () -> Unit) {
    val classifications = source.classifyCodes(input)
    val addableCodes = classifications.filter { it.willBeAdded }.map { it.code }
    val haptics = rememberAppHaptics()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(classifications, key = { it.code }) { classification ->
                Text(
                    text = classification.bulkText(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = PathEditMetrics.rowInset,
                        vertical = PathEditMetrics.rowVerticalPadding,
                    ),
                )
            }
        }
        Button(
            onClick = {
                val result = source.addCodes(input)
                if (result.added.isNotEmpty()) {
                    haptics.tap()
                    onHopsAdded()
                }
                onAdded()
            },
            enabled = addableCodes.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(PathEditMetrics.rowInset),
        ) {
            Text(
                if (addableCodes.isEmpty()) {
                    stringResource(Res.string.contacts_path_edit_bulk_add_empty)
                } else {
                    formattedStringResource(
                        Res.string.contacts_path_edit_bulk_add_action,
                        addableCodes.joinToString(", "),
                    )
                },
            )
        }
    }
}
