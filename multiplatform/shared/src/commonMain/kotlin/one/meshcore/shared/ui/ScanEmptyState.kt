package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val SCAN_EMPTY_PADDING = 24.dp
private val SCAN_PERMISSION_SPACING = 20.dp
private val SCAN_UNAVAILABLE_SPACING = 8.dp

/** Camera-permission empty state shared by contact and channel scanning. */
@Composable
internal fun ScanPermissionDenied(
    icon: ImageVector,
    glyphSize: Dp,
    title: String,
    message: String,
    settingsLabel: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScanEmptyColumn(
        icon = icon,
        glyphSize = glyphSize,
        title = title,
        message = message,
        spacing = SCAN_PERMISSION_SPACING,
        titleStyle = MaterialTheme.typography.titleLarge,
        modifier = modifier,
    ) {
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(settingsLabel)
        }
    }
}

/** No-camera empty state shared by contact and channel scanning. */
@Composable
internal fun ScanUnavailable(
    icon: ImageVector,
    glyphSize: Dp,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    ScanEmptyColumn(
        icon = icon,
        glyphSize = glyphSize,
        title = title,
        message = message,
        spacing = SCAN_UNAVAILABLE_SPACING,
        titleStyle = MaterialTheme.typography.titleMedium,
        modifier = modifier,
    )
}

/** Shared glyph, title, and message. Spacing and title style stay with each empty state. */
@Composable
private fun ScanEmptyColumn(
    icon: ImageVector,
    glyphSize: Dp,
    title: String,
    message: String,
    spacing: Dp,
    titleStyle: TextStyle,
    modifier: Modifier = Modifier,
    trailing: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(SCAN_EMPTY_PADDING),
        verticalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(glyphSize),
        )
        Text(
            title,
            style = titleStyle,
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        trailing()
    }
}
