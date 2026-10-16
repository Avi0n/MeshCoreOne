package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_request_timed_out
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubStatusRow
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun StatusSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    isLoading: Boolean,
    onReload: (() -> Unit)? = null,
    reloadAccessibilityLabel: String? = null,
    reloadEnabled: Boolean = true,
    isRadioReady: Boolean = true,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PreferenceHubSection {
            RemoteSectionHeader(
                title = title,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                isLoading = isLoading,
                onReload = onReload,
                reloadAccessibilityLabel = reloadAccessibilityLabel,
                reloadEnabled = reloadEnabled,
                isRadioReady = isRadioReady,
            )
            if (expanded) content()
        }
        if (expanded && footer != null) {
            PreferenceHubFooter(footer)
        }
    }
}

@Composable
internal fun SectionErrorText(error: NodeSectionError?) {
    val text = when (error) {
        NodeSectionError.Timeout, NodeSectionError.Failed ->
            stringResource(Res.string.remotenodes_remote_nodes_status_request_timed_out)
        is NodeSectionError.Message -> error.text
        null -> return
    }
    PreferenceHubStatusRow(text = text, color = MaterialTheme.colorScheme.error)
}
