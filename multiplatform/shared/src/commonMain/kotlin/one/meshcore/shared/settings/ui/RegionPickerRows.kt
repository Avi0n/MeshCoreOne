package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.chat.translation.platformPreferredLanguage
import one.meshcore.shared.map.RegionalAreas
import one.meshcore.shared.map.countryDisplayName
import one.meshcore.shared.map.subdivisionDisplayName
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_list_empty_search_title
import one.meshcore.shared.resources.localizable_common_search
import one.meshcore.shared.resources.onboarding_region_country
import one.meshcore.shared.resources.onboarding_region_province
import one.meshcore.shared.resources.onboarding_region_state
import one.meshcore.shared.resources.settings_radio_preset_location_not_set
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.ExpressiveSheetContent
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.modalSheetExpandedValues
import one.meshcore.shared.ui.pickerSheetSizeModifier
import org.jetbrains.compose.resources.stringResource

/**
 * Country and optional state pickers that write [RegionSelection] via
 * [RegionSelection.afterChoosingCountry] / [RegionSelection.afterChoosingSubdivision]. Searchable
 * sheets stand in for the iOS `RegionPickerRows` lists. The sheet uses the same drag-handle +
 * inline title as New Chat / Share Contact; a Close top bar would clone iOS sheet chrome.
 */
@Composable
internal fun RegionPickerRows(
    selection: RegionSelection?,
    onSelectionChange: (RegionSelection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notSet = stringResource(Res.string.settings_radio_preset_location_not_set)
    val countryId = selection?.countryCode
    var sheet by remember { mutableStateOf<RegionPickerSheet?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RegionPickerRow(
            label = stringResource(Res.string.onboarding_region_country),
            value = countryId?.let { countryDisplayName(it) } ?: notSet,
            onClick = { sheet = RegionPickerSheet.COUNTRY },
        )
        if (RegionalAreas.showsSubdivisionPicker(countryId)) {
            RegionPickerRow(
                label = administrativeAreaTitle(countryId),
                value = selection?.administrativeAreaCode?.let { subdivisionDisplayName(it) } ?: notSet,
                onClick = { sheet = RegionPickerSheet.SUBDIVISION },
            )
        }
    }

    when (sheet) {
        RegionPickerSheet.COUNTRY -> CountryPickerSheet(
            selectedCountry = countryId,
            onSelect = { id ->
                RegionSelection.afterChoosingCountry(newCountry = id, current = selection)
                    ?.let(onSelectionChange)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
        RegionPickerSheet.SUBDIVISION -> SubdivisionPickerSheet(
            country = countryId,
            selectedSubdivision = selection?.administrativeAreaCode,
            onSelect = { id ->
                RegionSelection.afterChoosingSubdivision(newSubdivision = id, current = selection)
                    ?.let(onSelectionChange)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

@Composable
private fun administrativeAreaTitle(countryCode: String?): String =
    when (RegionalAreas.administrativeAreaKind(countryCode)) {
        RegionalAreas.AdministrativeAreaKind.PROVINCE ->
            stringResource(Res.string.onboarding_region_province)
        RegionalAreas.AdministrativeAreaKind.STATE ->
            stringResource(Res.string.onboarding_region_state)
    }

@Composable
private fun RegionPickerRow(label: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                imageVector = AppIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button),
    )
}

@Composable
private fun CountryPickerSheet(selectedCountry: String?, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val countries = remember { RegionalAreas.countriesSortedByLocalizedName(platformPreferredLanguage()) }
    val options = countries.map { it.id to countryDisplayName(it.id) }
    RegionSearchSheet(
        title = stringResource(Res.string.onboarding_region_country),
        selectedId = selectedCountry,
        options = options,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SubdivisionPickerSheet(
    country: String?,
    selectedSubdivision: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = RegionalAreas.subdivisions(country)
        .map { it.id to subdivisionDisplayName(it.id) }
        .sortedBy { it.second }
    RegionSearchSheet(
        title = administrativeAreaTitle(country),
        selectedId = selectedSubdivision,
        options = options,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionSearchSheet(
    title: String,
    selectedId: String?,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = modalSheetExpandedValues,
    )
    var search by remember { mutableStateOf("") }
    val filtered = remember(options, search) {
        if (search.isEmpty()) {
            options
        } else {
            options.filter { it.second.contains(search, ignoreCase = true) }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ExpressiveSheetContent(modifier = pickerSheetSizeModifier(SHEET_HEIGHT_FRACTION)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            AppSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = stringResource(Res.string.localizable_common_search),
            )
            if (filtered.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.contacts_list_empty_search_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(filtered, key = { it.first }) { (id, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            leadingContent = {
                                RadioButton(selected = id == selectedId, onClick = null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = id == selectedId,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(id) },
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** Use-my-location control used on the Radio location section and the Location subpage. */
@Composable
internal fun PresetLocationUseMyLocationButton(
    isResolving: Boolean,
    locatingLabel: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = !isResolving,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(if (isResolving) locatingLabel else actionLabel)
    }
}

private enum class RegionPickerSheet { COUNTRY, SUBDIVISION }

private const val SHEET_HEIGHT_FRACTION = 0.85f
