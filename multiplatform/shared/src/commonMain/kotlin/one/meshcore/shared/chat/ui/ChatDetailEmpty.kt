package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import one.meshcore.shared.ui.EmptyMessageColumn

/** The path map has nothing to draw when no hop resolved to a coordinate. */
internal fun shouldShowPathMapUnavailable(locatedPointCount: Int): Boolean = locatedPointCount == 0

@Composable
internal fun ChatDetailEmptyPane(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    EmptyMessageColumn(
        title = title,
        icon = icon,
        description = description,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    )
}
