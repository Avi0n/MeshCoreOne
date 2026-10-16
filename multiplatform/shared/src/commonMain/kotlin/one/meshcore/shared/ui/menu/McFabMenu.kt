package one.meshcore.shared.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import one.meshcore.shared.ui.icons.AppIcons

/**
 * One labeled action in a [McFabMenu]. Prefer 2–6 items under a single primary intent.
 */
data class McFabMenuItem(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * FAB menu aligned to Material 3 FAB-menu baseline tokens (56dp items, 4dp gaps, full corners).
 * Platform [androidx.compose.material3] on this BOM has tokens only — no public FAB-menu composable.
 */
@Composable
fun McFabMenu(
    expandedContentDescription: String,
    collapsedIcon: ImageVector,
    items: List<McFabMenuItem>,
    modifier: Modifier = Modifier,
    collapsedContentDescription: String = expandedContentDescription,
) {
    require(items.size in FAB_MENU_MIN_ITEMS..FAB_MENU_MAX_ITEMS) {
        "McFabMenu requires $FAB_MENU_MIN_ITEMS–$FAB_MENU_MAX_ITEMS items, got ${items.size}"
    }
    var expanded by remember { mutableStateOf(false) }
    var fabBounds by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier = modifier.onGloballyPositioned { fabBounds = it.boundsInWindow() },
        contentAlignment = Alignment.BottomEnd,
    ) {
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics {
                contentDescription = if (expanded) expandedContentDescription else collapsedContentDescription
            },
        ) {
            Icon(
                imageVector = if (expanded) AppIcons.Close else collapsedIcon,
                contentDescription = null,
            )
        }
    }

    if (expanded) {
        val bounds = fabBounds
        val density = LocalDensity.current
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // Popup content is wrap-content on Android and collapsed this menu to the FAB anchor. A
            // full-width dialog gives the scrim and actions a stable window-sized coordinate space.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = false },
                )
                if (bounds != null) {
                    val endPad = with(density) { (constraints.maxWidth - bounds.right).toDp() }
                    val bottomPad = with(density) { (constraints.maxHeight - bounds.bottom).toDp() }
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = endPad.coerceAtLeast(0.dp),
                                bottom = bottomPad.coerceAtLeast(0.dp),
                            ),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(ITEM_GAP),
                            modifier = Modifier.padding(bottom = CLOSE_BUTTON_GAP),
                        ) {
                            items.forEach { item ->
                                FabMenuListItem(
                                    item = item,
                                    onClick = {
                                        if (item.enabled) {
                                            expanded = false
                                            item.onClick()
                                        }
                                    },
                                )
                            }
                        }
                        FloatingActionButton(
                            onClick = { expanded = false },
                            modifier = Modifier.semantics { contentDescription = expandedContentDescription },
                        ) {
                            Icon(imageVector = AppIcons.Close, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FabMenuListItem(item: McFabMenuItem, onClick: () -> Unit) {
    val container = MaterialTheme.colorScheme.secondaryContainer
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    val alpha = if (item.enabled) 1f else DISABLED_ALPHA
    Surface(
        onClick = onClick,
        enabled = item.enabled,
        shape = CircleShape,
        color = container.copy(alpha = alpha),
        contentColor = content.copy(alpha = alpha),
        tonalElevation = ITEM_ELEVATION,
        shadowElevation = ITEM_ELEVATION,
        modifier = Modifier
            .height(ITEM_HEIGHT)
            .semantics { contentDescription = item.label },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = ITEM_LEADING, end = ITEM_TRAILING),
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = ITEM_ICON_LABEL_GAP)
                    .size(ITEM_ICON_SIZE),
            )
            Text(text = item.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Validates item count for pure tests and construction. */
object McFabMenuRules {
    const val MIN_ITEMS: Int = 2
    const val MAX_ITEMS: Int = 6

    fun isValidItemCount(count: Int): Boolean = count in MIN_ITEMS..MAX_ITEMS
}

// Mirrored from material3 FabMenuBaselineTokens (internal on this BOM).
private val ITEM_HEIGHT = 56.dp
private val ITEM_GAP = 4.dp
private val CLOSE_BUTTON_GAP = 8.dp
private val ITEM_LEADING = 24.dp
private val ITEM_TRAILING = 24.dp
private val ITEM_ICON_SIZE = 24.dp
private val ITEM_ICON_LABEL_GAP = 8.dp
private val ITEM_ELEVATION = 6.dp
private const val DISABLED_ALPHA = 0.38f

/** Standard Material modal-scrim opacity behind the expanded menu. */
private const val SCRIM_ALPHA = 0.32f
private const val FAB_MENU_MIN_ITEMS = McFabMenuRules.MIN_ITEMS
private const val FAB_MENU_MAX_ITEMS = McFabMenuRules.MAX_ITEMS
