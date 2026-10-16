package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.billing.SideloadDistribution
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_support_title
import one.meshcore.shared.ui.PreferenceHubLinkRow
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.preferenceHubEdgeScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Sideload Support page. Purchases stay off. The links match the iOS `SIDELOAD` sponsors row.
 */
@Composable
internal fun SideloadDonationScreen(onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.preferenceHubEdgeScroll()) {
        PreferenceHubSection(applyHorizontalPadding = false) {
            SideloadDistribution.donationUrls.forEach { url ->
                PreferenceHubLinkRow(
                    stringResource(Res.string.settings_support_title),
                    ABOUT_DONATION_ICON,
                ) {
                    onOpenUrl(url)
                }
            }
        }
    }
}
