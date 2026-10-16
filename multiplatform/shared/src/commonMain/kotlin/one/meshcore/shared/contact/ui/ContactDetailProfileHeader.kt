package one.meshcore.shared.contact.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_avatar_choose_file
import one.meshcore.shared.resources.contacts_detail_avatar_choose_photo
import one.meshcore.shared.resources.contacts_detail_avatar_choose_source
import one.meshcore.shared.resources.contacts_detail_avatar_remove_photo
import one.meshcore.shared.resources.contacts_detail_avatar_saving_announcement
import one.meshcore.shared.resources.contacts_detail_blocked
import one.meshcore.shared.resources.contacts_detail_has_location
import one.meshcore.shared.ui.IMAGE_OVERLAY_CONTENT_COLOR
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.menu.McMenuRole
import org.jetbrains.compose.resources.stringResource

/** Header contact-avatar diameter (the Material analog of the iOS 150-pt `ContactAvatar`). */
private val DETAIL_AVATAR_SIZE = 96.dp
private val CONTACT_STATUS_TAG_ICON = 18.dp
private val AVATAR_EDIT_BADGE_SIZE = 28.dp
private val AVATAR_EDIT_BADGE_PADDING = 5.dp
private const val SAVING_SCRIM_ALPHA = 0.4f

/**
 * The editable chat-contact avatar: taps open a menu to choose a photo (where the platform picker is
 * available) or remove the current one, with a saving overlay while the new image is downscaled and
 * stored. Non-chat contacts keep their category [NodeAvatar] and are not editable, mirroring iOS.
 */
@Composable
private fun EditableContactAvatar(
    contact: ContactDto,
    isSaving: Boolean,
    onAvatarPicked: (ByteArray) -> Unit,
    onAvatarRemoved: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val picker = LocalAvatarImagePicker.current
    val hasAvatar = contact.avatarImageData?.isNotEmpty() == true
    val canEdit = picker.isSupported || picker.isFilePickerSupported || hasAvatar
    val savingAnnouncement = stringResource(Res.string.contacts_detail_avatar_saving_announcement)
    val chooseSource = stringResource(Res.string.contacts_detail_avatar_choose_source)
    val spoken = editableContactAvatarContentDescription(
        displayName = contact.displayName,
        actionLabel = if (isSaving) savingAnnouncement else chooseSource,
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(DETAIL_AVATAR_SIZE)
                .then(if (canEdit && !isSaving) Modifier.clickable { menuOpen = true } else Modifier)
                .then(
                    if (canEdit || isSaving) {
                        Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            ContactAvatar(contact, DETAIL_AVATAR_SIZE)
            if (isSaving) {
                Box(
                    modifier = Modifier
                        .size(DETAIL_AVATAR_SIZE)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = SAVING_SCRIM_ALPHA)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        color = IMAGE_OVERLAY_CONTENT_COLOR,
                        strokeWidth = 2.dp,
                    )
                }
            } else if (canEdit) {
                Icon(
                    imageVector = AppIcons.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(AVATAR_EDIT_BADGE_SIZE)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(AVATAR_EDIT_BADGE_PADDING),
                )
            }
        }
        McDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (picker.isSupported) {
                McMenuItem(
                    text = stringResource(Res.string.contacts_detail_avatar_choose_photo),
                    leadingIcon = AppIcons.AccountCircle,
                    onClick = {
                        menuOpen = false
                        picker.launch(onAvatarPicked)
                    },
                )
            }
            if (picker.isFilePickerSupported) {
                McMenuItem(
                    text = stringResource(Res.string.contacts_detail_avatar_choose_file),
                    leadingIcon = AppIcons.Archive,
                    onClick = {
                        menuOpen = false
                        picker.launchFromFiles(onAvatarPicked)
                    },
                )
            }
            if (hasAvatar) {
                McMenuItem(
                    text = stringResource(Res.string.contacts_detail_avatar_remove_photo),
                    leadingIcon = AppIcons.Delete,
                    role = McMenuRole.Destructive,
                    onClick = {
                        menuOpen = false
                        onAvatarRemoved()
                    },
                )
            }
        }
    }
}

@Composable
internal fun ProfileHeader(
    contact: ContactDto,
    typeLabel: String,
    isSavingAvatar: Boolean,
    onAvatarPicked: (ByteArray) -> Unit,
    onAvatarRemoved: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SECTION_HORIZONTAL, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (contact.contactType) {
                ContactType.CHAT -> EditableContactAvatar(
                    contact = contact,
                    isSaving = isSavingAvatar,
                    onAvatarPicked = onAvatarPicked,
                    onAvatarRemoved = onAvatarRemoved,
                )
                ContactType.REPEATER -> NodeAvatar(AvatarCategory.REPEATER, DETAIL_AVATAR_SIZE)
                ContactType.ROOM -> NodeAvatar(AvatarCategory.ROOM, DETAIL_AVATAR_SIZE)
            }
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Type, then the blocked / has-location status indicators, mirroring the iOS profile header
            // (which shows those two indicators, not a route chip).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactStatusBadge(label = typeLabel)
                if (contact.isBlocked) {
                    ContactStatusBadge(
                        label = stringResource(Res.string.contacts_detail_blocked),
                        icon = AppIcons.FrontHand,
                        contentColor = MaterialTheme.colorScheme.error,
                    )
                }
                if (contact.hasLocation) {
                    ContactStatusBadge(
                        label = stringResource(Res.string.contacts_detail_has_location),
                        icon = AppIcons.LocationOn,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Read-only type / blocked / location captions. Not chip chrome. */
@Composable
private fun ContactStatusBadge(
    label: String,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(CONTACT_STATUS_TAG_ICON),
            )
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = contentColor)
    }
}
