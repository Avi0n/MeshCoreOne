package one.meshcore.shared.channel.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.ui.AppNestedTopBar

/**
 * Shared chrome for a channel create/join sub-screen: [AppNestedTopBar] with Up (returns to the
 * Channel Options hub). Primary actions stay pinned in the body, not a trailing toolbar check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelFlowScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = title,
                onBack = onBack,
            )
        },
        content = content,
    )
}
