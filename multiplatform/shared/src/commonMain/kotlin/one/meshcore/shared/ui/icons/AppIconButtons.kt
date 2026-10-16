package one.meshcore.shared.ui.icons

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_thread_back_accessibility
import one.meshcore.shared.resources.localizable_common_close
import one.meshcore.shared.resources.localizable_common_save
import org.jetbrains.compose.resources.stringResource

/** Standard Material up / back affordance for hierarchical [TopAppBar] navigation. */
@Composable
fun NavigateBackIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(Res.string.chats_thread_back_accessibility),
    tint: Color = LocalContentColor.current,
) {
    AppIconButton(
        imageVector = AppIcons.Back,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        tint = tint,
    )
}

/** Standard Material close affordance for modal sheets and full-screen dismiss. */
@Composable
fun CloseIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(Res.string.localizable_common_close),
    tint: Color = LocalContentColor.current,
) {
    AppIconButton(
        imageVector = AppIcons.Close,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        tint = tint,
    )
}

/** Standard Material confirm / Save affordance for modal [TopAppBar] actions. */
@Composable
fun ConfirmIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = stringResource(Res.string.localizable_common_save),
    tint: Color = LocalContentColor.current,
) {
    AppIconButton(
        imageVector = AppIcons.Check,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tint = tint,
    )
}

@Composable
fun AppIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    iconModifier: Modifier = Modifier,
) {
    AppIconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = iconModifier,
        )
    }
}

@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier, content = content)
}
