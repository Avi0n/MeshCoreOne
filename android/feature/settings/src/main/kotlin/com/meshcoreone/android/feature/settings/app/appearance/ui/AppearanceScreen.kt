// PortedFrom: MC1/Views/Appearance/AppearanceView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Appearance/Components/ThemeSelectionCard.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: all themes are unlocked (no "Purchase More Themes" link); a single-column grid at large font scales (accessibility sizes).
package com.meshcoreone.android.feature.settings.app.appearance.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.PaletteSwatchSize
import com.meshcoreone.android.core.designsystem.ThemeCardMetrics
import com.meshcoreone.android.core.designsystem.ThemePaletteSwatch
import com.meshcoreone.android.core.designsystem.labelResource
import com.meshcoreone.android.core.designsystem.themeName
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.feature.settings.app.appearance.AppearanceController
import com.meshcoreone.android.feature.settings.app.appearance.AppearanceStateHolder
import com.meshcoreone.android.feature.settings.app.appearance.ThemeCard
import com.meshcoreone.android.feature.settings.app.appearance.appearanceSchemeChoices

private const val ACCESSIBILITY_FONT_SCALE = 1.5f

@Composable
fun AppearanceScreen(controller: AppearanceController, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val holder = remember(controller) { AppearanceStateHolder(controller, scope) }
    LaunchedEffect(holder) { holder.start() }
    val state by holder.state.collectAsState()
    val singleColumn = LocalDensity.current.fontScale >= ACCESSIBILITY_FONT_SCALE
    val columns = if (singleColumn) GridCells.Fixed(1) else GridCells.Adaptive(ThemeCardMetrics.GRID_ITEM_MINIMUM.dp)

    LazyVerticalGrid(
        columns = columns,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ThemeCardMetrics.GRID_HORIZONTAL_INSET.dp, ThemeCardMetrics.GRID_VERTICAL_INSET.dp),
        horizontalArrangement = Arrangement.spacedBy(ThemeCardMetrics.GRID_SPACING.dp),
        verticalArrangement = Arrangement.spacedBy(ThemeCardMetrics.GRID_SPACING.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.selectableGroup()) {
                Text(stringResource(S.appearanceSchemeHeader), style = MaterialTheme.typography.titleSmall)
                appearanceSchemeChoices.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().selectable(choice == state.colorScheme, role = Role.RadioButton) { holder.selectColorScheme(choice) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == state.colorScheme, onClick = null)
                        Text(stringResource(choice.labelResource), Modifier.padding(start = 8.dp))
                    }
                }
                Text(
                    stringResource(S.appearanceThemesHeader), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        items(state.cards, key = { it.theme.id.rawValue }) { card -> ThemeSelectionCard(card) { holder.selectTheme(card.theme) } }
    }
    if (state.failure != null) {
        AlertDialog(
            onDismissRequest = holder::dismissFailure,
            text = { Text(state.failure?.message ?: state.failure?.javaClass?.simpleName.orEmpty()) },
            confirmButton = { TextButton(onClick = holder::dismissFailure) { Text(stringResource(S.settingsBackupImportErrorDismiss)) } },
        )
    }
}

@Composable
private fun ThemeSelectionCard(card: ThemeCard, onSelect: () -> Unit) {
    val resources = LocalContext.current.resources
    val name = themeName(card.theme)
    val label = if (card.isSelected) S.appearanceAccessibilityThemeCardSelectedLabel(resources, name)
    else S.appearanceAccessibilityThemeCardOwnedLabel(resources, name)
    val hint = if (card.isSelected) "" else resources.getString(S.appearanceAccessibilityThemeCardOwnedHint)
    val shape = RoundedCornerShape(ThemeCardMetrics.CORNER_RADIUS.dp)
    Card(
        shape = shape,
        border = if (card.isSelected) BorderStroke(ThemeCardMetrics.SELECTION_STROKE_WIDTH.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !card.isSelected, role = Role.Button, onClickLabel = hint.ifEmpty { null }, onClick = onSelect)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Column(
            Modifier.padding(ThemeCardMetrics.CONTENT_PADDING.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemePaletteSwatch(card.theme, Modifier.fillMaxWidth().size(72.dp), PaletteSwatchSize.TAPPABLE)
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                if (card.isSelected) stringResource(S.appearanceThemesSelected) else "",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
