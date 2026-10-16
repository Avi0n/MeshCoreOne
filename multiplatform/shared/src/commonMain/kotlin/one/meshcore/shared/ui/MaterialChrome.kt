package one.meshcore.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.icons.CloseIconButton
import one.meshcore.shared.ui.icons.NavigateBackIconButton

/** Surface roles for the root and nested shell bars. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun shellTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
)

/** Caller actions, then the connection control, so that control stays at the trailing edge. */
@Composable
private fun RowScope.ShellBarActions(actions: @Composable RowScope.() -> Unit) {
    actions()
    ShellConnectionAction()
}

/** Pill / search-bar corner radius (M3 full-ish for search containers). */
private val SearchFieldShape = RoundedCornerShape(28.dp)

/** iOS `navigationHeader(titleIcon:)` identity avatar in the conversation/room bar. */
const val THREAD_HEADER_AVATAR_DP = 30

/** iOS `HeaderTitleLabel` HStack spacing between avatar and title. */
const val THREAD_HEADER_TITLE_GAP_DP = 10

/**
 * Root-tab [TopAppBar]: single-row title flush under the status bar (same row as actions).
 * Appends [ShellConnectionAction] last when the shell provides connection chrome.
 *
 * Do not use [androidx.compose.material3.LargeTopAppBar] here — its expanded second row leaves a
 * large empty band above the headline on list destinations that already host search and filters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRootTopBar(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        actions = { ShellBarActions(actions) },
        colors = shellTopBarColors(),
    )
}

/**
 * Nested / detail [TopAppBar] with tonal surface roles and single-line title. An optional [subtitle] adds a
 * centered second line (used by the chat header to show a channel's region).
 * [onBack] is the pushed-destination up affordance; [onClose] is the modal dismiss control. Prefer
 * [onBack] when both are set. Appends [ShellConnectionAction] last when the shell provides connection chrome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNestedTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleIcon: @Composable (() -> Unit)? = null,
    onTitleTap: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    closeContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            val titleBlock: @Composable () -> Unit = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(THREAD_HEADER_TITLE_GAP_DP.dp),
                ) {
                    titleIcon?.invoke()
                    if (subtitle.isNullOrEmpty()) {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Column {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (onTitleTap != null) {
                Row(modifier = Modifier.clickable(onClick = onTitleTap)) { titleBlock() }
            } else {
                titleBlock()
            }
        },
        modifier = modifier,
        navigationIcon = {
            when {
                onBack != null -> NavigateBackIconButton(onClick = onBack)
                onClose != null && closeContentDescription != null -> CloseIconButton(
                    onClick = onClose,
                    contentDescription = closeContentDescription,
                )
                onClose != null -> CloseIconButton(onClick = onClose)
            }
        },
        actions = { ShellBarActions(actions) },
        colors = shellTopBarColors(),
    )
}

/**
 * Contact Detail full-window overlays (status, settings, telemetry, CLI) dismiss with Up, matching
 * Saved History. Share and Connect Radio stay Close as modal tasks.
 */
@Composable
internal fun ContactDetailOverlayTopBar(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    AppNestedTopBar(
        title = title,
        modifier = modifier,
        onBack = onDismiss,
        actions = actions,
    )
}

/**
 * Filled, indicator-less search field (surface-container + leading search icon).
 * Prefer this over bare [OutlinedTextField] for list search chrome.
 */
@Composable
fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                CloseIconButton(
                    onClick = { onValueChange("") },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        singleLine = true,
        shape = SearchFieldShape,
        colors = filledFieldColors(),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

/**
 * Default M3 filled field colors: tonal container, no underline (pairs with rounded [Shape]).
 */
@Composable
fun filledFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    errorContainerColor = MaterialTheme.colorScheme.errorContainer,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * Outlined form field with medium corner radius (M3 text-field shape), not the razor MD2 outline.
 * Prefer this over bare [OutlinedTextField] for settings, tools, and onboarding forms.
 */
@Composable
fun AppOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: TextFieldColors = appOutlinedTextFieldColors(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = shape,
        colors = colors,
    )
}

/** Default outlined field colors for [AppOutlinedTextField] (tonal surface, primary focus). */
@Composable
fun appOutlinedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    errorBorderColor = MaterialTheme.colorScheme.error,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    errorContainerColor = MaterialTheme.colorScheme.surface,
)

/**
 * Trailing clear control for a filled field, matching iOS `WiFiAddressFields` xmark when the
 * value is non-empty. [contentDescription] is the spoken label (Clear hostname or IP / Clear port).
 */
@Composable
fun textFieldClearTrailingIcon(
    value: String,
    contentDescription: String,
    enabled: Boolean = true,
    onClear: () -> Unit,
): @Composable (() -> Unit)? {
    if (value.isEmpty()) return null
    return {
        AppIconButton(
            imageVector = AppIcons.Close,
            contentDescription = contentDescription,
            onClick = onClear,
            enabled = enabled,
        )
    }
}
