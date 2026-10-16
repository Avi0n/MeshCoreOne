package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.distanceLabel
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.contact.idPrefixHex
import one.meshcore.shared.contact.recencyTimestamp
import one.meshcore.shared.contact.relativeAgeLabel
import one.meshcore.shared.contact.routeKind
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.remotenode.LPPDisplay
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_row_away
import one.meshcore.shared.resources.contacts_row_blocked
import one.meshcore.shared.resources.contacts_row_favorite
import one.meshcore.shared.resources.contacts_row_location
import one.meshcore.shared.ui.FAVORITE_STAR_ROLE
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

/**
 * The visual content of a Nodes-list row without the long-press context menu, so display-only surfaces
 * (Blocked Contacts) reuse the exact avatar / name / route / distance / timestamp layout. [modifier]
 * carries the row's click behavior.
 */
@Composable
fun ContactRowContent(
    contact: ContactDto,
    showTypeLabel: Boolean,
    /** The connected radio's key-prefix width in bytes (iOS `connectedDevice?.hashSize ?? 1`). */
    hashSize: Int,
    isTogglingFavorite: Boolean,
    inboundHopCount: Int?,
    userLocation: UserLocation?,
    nowSeconds: Long,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        leadingContent = { Avatar(contact) },
        headlineContent = { Headline(contact, hashSize) },
        supportingContent = { SecondaryLine(contact, showTypeLabel, inboundHopCount, userLocation) },
        trailingContent = {
            TrailingMeta(contact = contact, isTogglingFavorite = isTogglingFavorite, nowSeconds = nowSeconds)
        },
    )
}

@Composable
private fun Avatar(contact: ContactDto) {
    when (contact.contactType) {
        ContactType.CHAT -> ContactAvatar(contact, CONTACT_ROW_AVATAR_SIZE)
        ContactType.REPEATER -> NodeAvatar(AvatarCategory.REPEATER, CONTACT_ROW_AVATAR_SIZE)
        ContactType.ROOM -> NodeAvatar(AvatarCategory.ROOM, CONTACT_ROW_AVATAR_SIZE)
    }
}

@Composable
private fun Headline(contact: ContactDto, hashSize: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Prefix stays on screen; TalkBack speaks only the name, matching iOS ContactRowView.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    // Speak the name; the id-prefix hex is visual.
                    contentDescription = contact.displayName
                },
        ) {
            Text(
                text = contact.idPrefixHex(hashSize),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (contact.isBlocked) {
            val blockedLabel = stringResource(Res.string.contacts_row_blocked)
            Icon(
                imageVector = AppIcons.FrontHand,
                contentDescription = blockedLabel,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(ROW_STATUS_ICON_SIZE),
            )
        }
    }
}

@Composable
private fun TrailingMeta(contact: ContactDto, isTogglingFavorite: Boolean, nowSeconds: Long) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isTogglingFavorite) {
                CircularProgressIndicator(modifier = Modifier.size(FAVORITE_SPINNER_SIZE), strokeWidth = 2.dp)
            } else if (contact.isFavorite) {
                val favoriteLabel = stringResource(Res.string.contacts_row_favorite)
                Icon(
                    imageVector = AppIcons.Star,
                    contentDescription = favoriteLabel,
                    tint = FAVORITE_STAR_ROLE.tint(),
                    modifier = Modifier.size(ROW_STATUS_ICON_SIZE),
                )
            }
            Text(
                text = relativeAgeLabel(contact.recencyTimestamp, nowSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SecondaryLine(
    contact: ContactDto,
    showTypeLabel: Boolean,
    inboundHopCount: Int?,
    userLocation: UserLocation?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showTypeLabel) {
            Text(
                text = contact.contactType.kindLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = contact.routeKind(inboundHopCount).label(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (contact.hasLocation) {
            val locationLabel = stringResource(Res.string.contacts_row_location)
            Icon(
                imageVector = AppIcons.LocationOn,
                contentDescription = locationLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ROW_STATUS_ICON_SIZE),
            )
            contact.distanceLabel(
                userLocation,
                LPPDisplay.currentMeasurementSystem(),
                LPPDisplay.currentLengthSymbols(),
            )?.let { distance ->
                Text(
                    text = formattedStringResource(Res.string.contacts_row_away, distance),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val FAVORITE_SPINNER_SIZE = 16.dp
private val ROW_STATUS_ICON_SIZE = 16.dp
