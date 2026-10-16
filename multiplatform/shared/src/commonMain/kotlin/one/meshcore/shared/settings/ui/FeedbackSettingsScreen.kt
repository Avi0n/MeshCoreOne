package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_feedback_email_link
import one.meshcore.shared.resources.settings_feedback_git_hub_link
import one.meshcore.shared.resources.settings_feedback_header_body
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubLinkRow
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → About → Feedback: GitHub Issues, developer email, and diagnostics export, matching
 * iOS `FeedbackView`.
 */
@Composable
fun FeedbackSettingsScreen(
    onOpenUrl: (String) -> Unit,
    onExportLogs: suspend () -> String = { "" },
    onClearLogs: suspend () -> Unit = {},
    onShareExport: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.preferenceHubScroll()) {
        PreferenceHubFooter(stringResource(Res.string.settings_feedback_header_body))
        PreferenceHubSection {
            PreferenceHubLinkRow(
                label = stringResource(Res.string.settings_feedback_git_hub_link),
                icon = AppIcons.Terminal,
                onClick = { onOpenUrl(FEEDBACK_GITHUB_ISSUES_URL) },
            )
            PreferenceHubLinkRow(
                label = stringResource(Res.string.settings_feedback_email_link),
                icon = AppIcons.AlternateEmail,
                onClick = { onOpenUrl(SUPPORT_CONTACT_MAILTO) },
            )
        }
        DiagnosticsLogSection(
            onExportLogs = onExportLogs,
            onClearLogs = onClearLogs,
            onShareExport = onShareExport,
            modifier = Modifier.padding(top = PreferenceHubSectionGap),
        )
    }
}

/** GitHub Issues tracker (iOS `FeedbackView.FeedbackContact.issuesURL`). */
const val FEEDBACK_GITHUB_ISSUES_URL = "https://github.com/Avi0n/MeshCoreOne/issues"
