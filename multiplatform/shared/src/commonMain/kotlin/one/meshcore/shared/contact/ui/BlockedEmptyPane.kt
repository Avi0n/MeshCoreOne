package one.meshcore.shared.contact.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.ui.IconLeadEmptyColumn
import one.meshcore.shared.ui.icons.AppIcons

/** Centered blocked-list empty state: the same glyph, title, and description spacing. */
@Composable
internal fun BlockedEmptyPane(title: String, description: String, modifier: Modifier = Modifier) {
    IconLeadEmptyColumn(
        title = title,
        description = description,
        icon = AppIcons.DoNotTouch,
        modifier = modifier,
    )
}
