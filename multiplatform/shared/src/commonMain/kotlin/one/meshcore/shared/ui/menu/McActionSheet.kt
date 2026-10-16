package one.meshcore.shared.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import one.meshcore.shared.ui.ExpressiveSheetContent
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.modalSheetPartialValues

/**
 * Modal bottom sheet for multi-action lists (row long-press, etc.). Hosts [McActionSheetItem] rows
 * with the same role/icon rules as dropdown menus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McActionSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = modalSheetPartialValues,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        ExpressiveSheetContent(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = SHEET_BOTTOM_PADDING),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = SHEET_H_PADDING, vertical = SHEET_TITLE_V_PADDING),
                )
            }
            content()
        }
    }
}

@Composable
fun McActionSheetItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    trailingRotationDegrees: Float = 0f,
    stateDescription: String? = null,
    role: McMenuRole = McMenuRole.Default,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val effectiveRole = mcMenuEffectiveRole(role, selected)
    val labelColor = mcMenuLabelColor(effectiveRole).mcMenuEnabledColor(enabled)
    val iconColor = mcMenuIconColor(effectiveRole).mcMenuEnabledColor(enabled)
    val spokenState = stateDescription
    val showCheck = mcMenuShowsCheck(role, selected)
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = text
                this.role = Role.Button
                if (spokenState != null) {
                    this.stateDescription = spokenState
                }
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        headlineContent = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
            )
        },
        leadingContent = leadingIcon?.let { icon ->
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                )
            }
        },
        trailingContent = when {
            trailingIcon != null -> {
                {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(TRAILING_ICON_SIZE)
                            .rotate(trailingRotationDegrees),
                    )
                }
            }
            showCheck -> {
                {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        tint = iconColor,
                    )
                }
            }
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun McActionSheetDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = DIVIDER_V_PADDING),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Non-interactive section label inside a sheet (Material 3 list section header). */
@Composable
fun McActionSheetSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = SECTION_HEADER_H_PADDING,
                end = SECTION_HEADER_H_PADDING,
                top = SECTION_HEADER_TOP_PADDING,
                bottom = SECTION_HEADER_BOTTOM_PADDING,
            ),
    )
}

/** Optional non-interactive header block above action rows (e.g. node name + battery). */
@Composable
fun McActionSheetHeader(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SHEET_H_PADDING, vertical = HEADER_V_PADDING),
        content = content,
    )
}

private val SHEET_H_PADDING = 24.dp
private val SHEET_BOTTOM_PADDING = 16.dp
private val SHEET_TITLE_V_PADDING = 8.dp
private val TRAILING_ICON_SIZE = 16.dp
private val DIVIDER_V_PADDING = 4.dp
private val HEADER_V_PADDING = 12.dp
private val SECTION_HEADER_H_PADDING = 16.dp
private val SECTION_HEADER_TOP_PADDING = 12.dp
private val SECTION_HEADER_BOTTOM_PADDING = 8.dp
