package one.meshcore.shared.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Nested bar when this destination owns its title. A parent that already titled it gets the body
 * with empty padding, and the caller's modifier is not applied.
 */
@Composable
fun OptionalNestedScaffold(
    showAppBar: Boolean,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (showAppBar) {
        Scaffold(
            modifier = modifier,
            topBar = { AppNestedTopBar(title = title, onBack = onBack) },
            content = content,
        )
    } else {
        content(PaddingValues())
    }
}
