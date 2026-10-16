package one.meshcore.shared.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.remotenode.NodeAuthPurpose

/**
 * The modal sheets the shell presents above the tab content: contact add/scan/share and the channel
 * create/join hub, plus the two deep-link confirmations a `meshcore://` contact/channel link stages. The
 * iOS shell presents these as `.sheet`s over `ContentView`; here [ShellSheetHost] overlays both the compact
 * and wide shells with them, so the affordances stay shell-agnostic (a tab toolbar action or a staged deep
 * link sets the state; the host renders it).
 */
sealed interface ShellSheet {
    /** The manual Add-contact form (a Nodes-toolbar affordance). */
    data object AddContact : ShellSheet

    /** The contact QR camera scanner, reached from the Add-contact form's Scan action. */
    data object ScanContact : ShellSheet

    /** The connected radio's own contact QR ("Share My Contact"). */
    data object ShareMyContact : ShellSheet

    /** Share another node's contact card as QR / URI. */
    data class ShareContact(val contact: ContactDto) : ShellSheet

    /** The channel create/join hub (the Chats "New Channel" affordance). */
    data object ChannelOptions : ShellSheet

    /** The add-contact confirmation staged by a `meshcore://contact/add` link. */
    data class ConfirmAddContact(val contact: MeshCoreDeepLink.Contact) : ShellSheet

    /** The join-channel confirmation staged by a `meshcore://channel/add` link. */
    data class JoinChannelLink(val channel: MeshCoreDeepLink.Channel) : ShellSheet

    /** The join-channel confirmation staged by a tapped `#hashtag` in a message. */
    data class JoinHashtagFromMessage(val rawChannelName: String) : ShellSheet

    /**
     * Mention-tap disambiguation: empty matches, self-mention, or multiple contacts with the same name.
     * A single-match mention navigates to contact detail without a sheet.
     */
    data class MentionPicker(val name: String, val matches: List<ContactDto>, val isSelfMention: Boolean) : ShellSheet

    /** The routing path editor for [contact]. */
    data class EditPath(val contact: ContactDto) : ShellSheet

    /**
     * The remote-node authentication sheet for a repeater or room [contact], carrying the resolved [role]
     * and requested [purpose].
     */
    data class NodeAuth(
        val contact: ContactDto,
        val role: RemoteNodeRole,
        val purpose: NodeAuthPurpose = NodeAuthPurpose.JOIN,
    ) : ShellSheet

    /**
     * The disconnected-room pre-auth gate a list, notification, or deep-link room tap stages instead of
     * opening the thread directly, carrying the room's session id. Mirrors iOS `RoomAuthenticationSheet`:
     * resolves the session's contact, then shows [one.meshcore.shared.remotenode.ui.NodeAuthenticationSheet]
     * for the room-server role.
     */
    data class RoomAuth(val sessionId: String) : ShellSheet

    /** Repeater status counters for [contact]; requires a connected admin session. */
    data class NodeStatus(val contact: ContactDto) : ShellSheet

    /**
     * Offline / Room-backed telemetry history overview for [contact] (iOS Telemetry History push).
     * Does not require a live admin session.
     */
    data class TelemetryHistory(val contact: ContactDto) : ShellSheet

    /**
     * Live request-telemetry disclosure for [contact] (iOS `NodeTelemetryView`). Chat peers use this
     * from contact detail Telemetry; does not require a remote-node admin session.
     */
    data class NodeTelemetry(val contact: ContactDto) : ShellSheet

    /** Node CLI terminal for [contact]; requires a connected admin session. */
    data class NodeCli(val contact: ContactDto) : ShellSheet

    /** Remote repeater settings for [contact]; requires a connected admin session. */
    data class NodeSettings(val contact: ContactDto) : ShellSheet

    /** Room-server status after contact-detail Telemetry / guest manage. */
    data class RoomStatus(val contact: ContactDto) : ShellSheet

    /** Room-server settings after contact-detail Management admin login. */
    data class RoomSettings(val contact: ContactDto) : ShellSheet

    /**
     * Room conversation for [contact]. Opened after room auth succeeds, and from the chats-tab room
     * conversation row when the room-server contact can be resolved by public key.
     */
    data class RoomConversation(val contact: ContactDto) : ShellSheet

    /**
     * Radio connect / change-device flow: BLE scan picker + WiFi entry. Opened from connection chrome,
     * the disconnected status pill, and other "Connect" CTAs — never by switching to the Nodes tab.
     */
    data object ConnectRadio : ShellSheet

    /**
     * Sheets that show last-radio identity or a radio-scoped confirm. Last-radio forget and a radio
     * switch dismiss them. Connect, add, scan, and channel-options stay so the user can pick a new radio.
     */
    val dismissOnRadioTeardown: Boolean
        get() = when (this) {
            ConnectRadio, AddContact, ScanContact, ChannelOptions -> false
            else -> true
        }

    /**
     * Nested full-screen sheet system Back returns to, matching iOS `navigationDestination` inside
     * the parent sheet. Null dismisses the overlay.
     */
    val backParent: ShellSheet?
        get() = when (this) {
            ScanContact -> AddContact
            else -> null
        }
}
