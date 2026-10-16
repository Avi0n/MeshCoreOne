package one.meshcore.shared.contact.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_close

/** TalkBack for the Close control that aborts in-flight path discovery. Close icons speak Close. */
internal fun discoveringPathAbortSpokenResource() = Res.string.localizable_common_close

/** iOS `ContactDetailAvatarView` VoiceOver: name, then choose-source or saving. */
internal fun editableContactAvatarContentDescription(displayName: String, actionLabel: String): String =
    "$displayName, $actionLabel"
