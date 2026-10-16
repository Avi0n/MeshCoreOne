package one.meshcore.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.ui.icons.AppIcons

/**
 * Horizontal inset matching Android system Settings preference groups (16dp from screen edge).
 */
val PreferenceHubHorizontalPadding = 16.dp

/** Space above the first hub section under the app bar. */
val PreferenceHubTopPadding = 8.dp

/** Vertical gap between preference group cards. */
val PreferenceHubSectionGap = 16.dp

/** Inner padding for card content that is not a full-bleed [androidx.compose.material3.ListItem]. */
val PreferenceHubCardPadding = 16.dp

/** Space above a full-width action that sits under card content. */
val PreferenceHubCardActionTop = 8.dp

/** Every-edge inset for a scrolling settings column. */
val PreferenceHubEdgePadding = 16.dp

/** Extra inset so a footer lines up with the section label (16dp past the group’s screen inset). */
val PreferenceHubFooterInset = 16.dp

/** Filled marker for a compact checklist row inside a preference card. */
val PreferenceHubBulletMarkerSize = 6.dp

/** Gap between the marker and the row text. */
val PreferenceHubBulletGap = 12.dp

/** Vertical space between stacked [PreferenceHubBulletRow]s. */
val PreferenceHubBulletRowGap = 4.dp

/** Drops the marker to the first line of [androidx.compose.material3.MaterialTheme.typography.bodyMedium]. */
val PreferenceHubBulletMarkerTop = 7.dp

/**
 * System-Settings-style preference group: optional primary-colored section label over a tonal
 * rounded card. Rows go in [content] as [androidx.compose.material3.ListItem]s without per-row
 * dividers (AOSP Settings groups rely on card chrome, not MD2 dividers).
 */
@Composable
fun PreferenceHubSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    /**
     * When false, the group already sits inside a padded column (Advanced) and must not add a second
     * 16dp screen inset.
     */
    applyHorizontalPadding: Boolean = true,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (applyHorizontalPadding) {
                    Modifier.padding(horizontal = PreferenceHubHorizontalPadding)
                } else {
                    Modifier
                },
            ),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = PreferenceHubHeaderInset,
                    end = PreferenceHubHeaderInset,
                    top = PreferenceHubHeaderTop,
                    bottom = PreferenceHubHeaderBottom,
                ),
            )
        } else {
            // Match header top spacing so untitled groups (Tools) sit at the same density.
            Spacer(modifier = Modifier.height(PreferenceHubHeaderTop))
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = contentModifier, content = content)
        }
    }
}

/**
 * Caption under a [PreferenceHubSection] card, inset to match the section label.
 */
@Composable
fun PreferenceHubFooter(text: String, modifier: Modifier = Modifier, applyHorizontalPadding: Boolean = true) {
    val screenInset = if (applyHorizontalPadding) PreferenceHubHorizontalPadding else 0.dp
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = screenInset + PreferenceHubFooterInset,
                end = screenInset + PreferenceHubFooterInset,
                top = 8.dp,
            ),
    )
}

/**
 * A label with its value underneath, full-bleed in a hub card.
 * [monospace] draws the value in a monospace body style. [icon] adds a leading glyph.
 */
@Composable
internal fun PreferenceHubValueRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    icon: ImageVector? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            if (monospace) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingContent = icon?.let { image ->
            { Icon(imageVector = image, contentDescription = null) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A label with a selectable monospace value underneath, full-bleed in a hub card.
 */
@Composable
internal fun PreferenceHubSelectableValueRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A full-bleed radio choice in a hub card. The radio does not take the click; the row does.
 */
@Composable
internal fun PreferenceHubRadioRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    )
}

/**
 * A full-bleed external link in a hub card: a leading glyph and a trailing link icon.
 */
@Composable
internal fun PreferenceHubLinkRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Icon(
                AppIcons.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/**
 * In-card status copy as a full-bleed ListItem, not a padded caption.
 */
@Composable
fun PreferenceHubStatusRow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    ListItem(
        headlineContent = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A compact checklist row: theme-colored marker plus body text.
 */
@Composable
fun PreferenceHubBulletRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PreferenceHubBulletGap),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = PreferenceHubBulletMarkerTop)
                .size(PreferenceHubBulletMarkerSize)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

private val PreferenceHubHeaderInset = 16.dp
private val PreferenceHubHeaderTop = 12.dp
private val PreferenceHubHeaderBottom = 8.dp

/** Root hub list: the root bar, then a lazy column inset by the preference padding. */
@Composable
internal fun PreferenceHubList(
    title: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { AppRootTopBar(title = title) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            state = listState,
            contentPadding = PaddingValues(
                top = PreferenceHubTopPadding,
                bottom = PreferenceHubSectionGap,
            ),
            content = content,
        )
    }
}

/** Fills the width, scrolls, then pads inside the scroll by the hub inset. */
@Composable
fun Modifier.preferenceHubScroll(): Modifier = fillMaxWidth()
    .verticalScroll(rememberScrollState())
    .padding(top = PreferenceHubTopPadding, bottom = PreferenceHubSectionGap)
