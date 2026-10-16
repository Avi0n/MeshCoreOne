package one.meshcore.shared.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.distanceLabel
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.contact.relativeAgeLabel
import one.meshcore.shared.contact.ui.ContactAvatar
import one.meshcore.shared.contact.ui.kindLabel
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.remotenode.LPPDisplay
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_contact_match_accessibility_not_selected
import one.meshcore.shared.resources.chats_contact_match_accessibility_selected
import one.meshcore.shared.resources.chats_contact_match_key
import one.meshcore.shared.resources.contacts_row_away
import one.meshcore.shared.resources.contacts_row_location
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/** How a candidate row is picked: a multi-select toggle (block) or a plain tap (send DM). */
internal sealed interface ContactMatchSelection {
    data class Toggle(val isSelected: Boolean) : ContactMatchSelection
    data object Tap : ContactMatchSelection
}

/** The detail lines a candidate row draws under the name, in order. */
internal enum class ContactMatchRowLine { NAME, LAST_ADVERT, CONTACT_TYPE, LOCATION, DISTANCE, PUBLIC_KEY }

/**
 * The detail a candidate row shows for [contact]. Two contacts can share the sender name the sheet is
 * about, so every line that tells them apart is present: the last-advert age, the node kind, the coordinate
 * and its distance when both ends are known, and the full public key — the only unambiguous identity.
 * Mirrors the Swift `ContactMatchRow` body.
 */
internal fun contactMatchRowLines(contact: ContactDto, userLocation: UserLocation?): List<ContactMatchRowLine> =
    buildList {
        add(ContactMatchRowLine.NAME)
        add(ContactMatchRowLine.LAST_ADVERT)
        add(ContactMatchRowLine.CONTACT_TYPE)
        if (contact.hasLocation) {
            add(ContactMatchRowLine.LOCATION)
            if (contact.distanceLabel(userLocation) != null) add(ContactMatchRowLine.DISTANCE)
        }
        add(ContactMatchRowLine.PUBLIC_KEY)
    }

/** The public key as space-separated uppercase hex pairs, matching iOS `uppercaseHexString(separator: " ")`. */
internal fun spacedKeyHex(bytes: ByteArray): String = bytes.toUppercaseHex().chunked(HEX_PAIR).joinToString(" ")

/**
 * Toggle rows speak Selected / Not selected as the accessibility value, matching iOS
 * `ContactMatchRow.accessibilityValue`. Tap rows have no extra value.
 */
internal fun contactMatchStateDescription(
    selection: ContactMatchSelection,
    selected: String,
    notSelected: String,
): String? = when (selection) {
    is ContactMatchSelection.Toggle -> if (selection.isSelected) selected else notSelected
    ContactMatchSelection.Tap -> null
}

/**
 * One contact the sheet offers as the identity behind a channel sender name. Shared by the block-sender and
 * send-DM sheets so both present the same evidence, as the one Swift `ContactMatchRow` does.
 */
@Composable
internal fun ContactMatchRow(
    contact: ContactDto,
    selection: ContactMatchSelection,
    userLocation: UserLocation?,
    nowSeconds: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spokenName = contact.displayName
    val selectedLabel = stringResource(Res.string.chats_contact_match_accessibility_selected)
    val notSelectedLabel = stringResource(Res.string.chats_contact_match_accessibility_not_selected)
    val stateSpoken = contactMatchStateDescription(selection, selectedLabel, notSelectedLabel)
    val lines = contactMatchRowLines(contact, userLocation)
    ListItem(
        headlineContent = { MatchRowLine(ContactMatchRowLine.NAME, contact, userLocation, nowSeconds) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lines.filter { it != ContactMatchRowLine.NAME }.forEach { line ->
                    MatchRowLine(line, contact, userLocation, nowSeconds)
                }
            }
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selection is ContactMatchSelection.Toggle) {
                    Checkbox(
                        checked = selection.isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                ContactAvatar(contact = contact, size = AVATAR_SIZE)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = spokenName
                when (selection) {
                    is ContactMatchSelection.Toggle -> {
                        role = Role.Switch
                        if (stateSpoken != null) stateDescription = stateSpoken
                    }
                    ContactMatchSelection.Tap -> role = Role.Button
                }
            },
    )
}

@Composable
private fun MatchRowLine(
    line: ContactMatchRowLine,
    contact: ContactDto,
    userLocation: UserLocation?,
    nowSeconds: Long,
) = when (line) {
    ContactMatchRowLine.NAME -> Text(
        text = contact.displayName,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
    )

    ContactMatchRowLine.LAST_ADVERT -> SecondaryLabel(relativeAgeLabel(contact.lastAdvertTimestamp, nowSeconds))

    ContactMatchRowLine.CONTACT_TYPE -> SecondaryLabel(contact.contactType.kindLabel())

    ContactMatchRowLine.LOCATION -> Icon(
        imageVector = AppIcons.LocationOn,
        contentDescription = stringResource(Res.string.contacts_row_location),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(LOCATION_ICON_SIZE),
    )

    ContactMatchRowLine.DISTANCE -> SecondaryLabel(
        formattedStringResource(
            Res.string.contacts_row_away,
            contact.distanceLabel(
                userLocation,
                LPPDisplay.currentMeasurementSystem(),
                LPPDisplay.currentLengthSymbols(),
            ).orEmpty(),
        ),
    )

    ContactMatchRowLine.PUBLIC_KEY -> Text(
        text = formattedStringResource(Res.string.chats_contact_match_key, spacedKeyHex(contact.publicKey)),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SecondaryLabel(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

private const val HEX_PAIR = 2
private val AVATAR_SIZE = 40.dp
private val LOCATION_ICON_SIZE = 16.dp
