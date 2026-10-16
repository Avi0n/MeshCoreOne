package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.meshcore.shared.billing.SideloadDistribution
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_about_github
import one.meshcore.shared.resources.settings_about_header
import one.meshcore.shared.resources.settings_about_online_map
import one.meshcore.shared.resources.settings_about_privacy_policy
import one.meshcore.shared.resources.settings_about_website
import one.meshcore.shared.resources.settings_build
import one.meshcore.shared.resources.settings_feedback_title
import one.meshcore.shared.resources.settings_support_title
import one.meshcore.shared.resources.settings_version
import one.meshcore.shared.ui.PreferenceHubLinkRow
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/** Public link targets for the About section, matching iOS `AboutSection`. */
private const val WEBSITE_URL = "https://meshcore.io"
private const val ONLINE_MAP_URL = "https://map.meshcore.io/"
private const val GITHUB_URL = "https://github.com/Avi0n/MeshCoreOne"
private const val PRIVACY_POLICY_URL = "https://meshcoreone.com/privacy.html"

/**
 * Settings hub About section: Feedback plus outbound links (website, online map, GitHub, privacy
 * policy) plus the app version/build footer. Mirrors iOS `AboutSection` + the version footer in
 * `SettingsListContent`. A sideload build puts the Support row here as a donation link, matching the
 * iOS `SIDELOAD` About section. The privacy-policy link stays for a later Play build.
 */
@Composable
fun AboutSection(
    onOpenUrl: (String) -> Unit,
    onOpenFeedback: () -> Unit,
    appVersion: String?,
    appBuild: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        PreferenceHubSection(title = stringResource(Res.string.settings_about_header)) {
            if (!SideloadDistribution.BILLING_ENABLED) {
                SideloadDistribution.donationUrls.forEach { url ->
                    PreferenceHubLinkRow(
                        stringResource(Res.string.settings_support_title),
                        ABOUT_DONATION_ICON,
                    ) {
                        onOpenUrl(url)
                    }
                }
            }
            DestinationLinkRow(
                label = stringResource(Res.string.settings_feedback_title),
                icon = ABOUT_FEEDBACK_ICON,
                onClick = onOpenFeedback,
            )
            PreferenceHubLinkRow(stringResource(Res.string.settings_about_website), ABOUT_WEBSITE_ICON) {
                onOpenUrl(WEBSITE_URL)
            }
            PreferenceHubLinkRow(stringResource(Res.string.settings_about_online_map), ABOUT_MAP_ICON) {
                onOpenUrl(ONLINE_MAP_URL)
            }
            PreferenceHubLinkRow(stringResource(Res.string.settings_about_github), ABOUT_GITHUB_ICON) {
                onOpenUrl(GITHUB_URL)
            }
            PreferenceHubLinkRow(stringResource(Res.string.settings_about_privacy_policy), ABOUT_PRIVACY_ICON) {
                onOpenUrl(PRIVACY_POLICY_URL)
            }
        }
        if (appVersion != null || appBuild != null) {
            Text(
                text = versionFooter(appVersion, appBuild),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun DestinationLinkRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun versionFooter(appVersion: String?, appBuild: String?): String {
    val version = appVersion?.let { formattedStringResource(Res.string.settings_version, it) }
    val build = appBuild?.let { formattedStringResource(Res.string.settings_build, it) }
    return joinVersionFooterParts(version, build)
}

internal fun joinVersionFooterParts(version: String?, build: String?): String =
    listOfNotNull(version, build).joinToString(", ")

/** iOS sideload Support row: `heart`. */
internal val ABOUT_DONATION_ICON get() = AppIcons.Favorite

/** iOS `AboutSection` Feedback: `exclamationmark.bubble`. */
internal val ABOUT_FEEDBACK_ICON get() = AppIcons.Feedback

/** iOS `AboutSection` website: `globe`. */
internal val ABOUT_WEBSITE_ICON get() = AppIcons.Public

/** iOS `AboutSection` online map: `map`. */
internal val ABOUT_MAP_ICON get() = AppIcons.Map

/** iOS `AboutSection` GitHub: `chevron.left.forwardslash.chevron.right`. */
internal val ABOUT_GITHUB_ICON get() = AppIcons.Code

/** iOS `AboutSection` privacy policy: `hand.raised`. */
internal val ABOUT_PRIVACY_ICON get() = AppIcons.FrontHand
