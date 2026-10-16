package one.meshcore.shared.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.MessageStatusEvent
import one.meshcore.shared.connection.CompanionDeviceRegistry
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.NoOpCompanionDeviceRegistry
import one.meshcore.shared.connection.StatusPillState
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.onboarding.OnboardingViewModel
import one.meshcore.shared.room.RoomServerEvent

interface ShellSessionHost {
    val coordinator: NavigationCoordinator

    val onboarding: OnboardingViewModel

    /**
     * The onboarding-complete flag read once at launch; the root shows onboarding until it
     * (or [OnboardingViewModel.completed]) is true.
     */
    val initialOnboardingComplete: Boolean

    val statusPill: StateFlow<StatusPillState>

    val battery: StateFlow<BatteryInfo?>

    /** The connected radio's active OCV curve for turning [battery] millivolts into a percentage. */
    val batteryOcvArray: StateFlow<List<Int>>

    val refreshBattery: suspend () -> Unit

    val connectionState: StateFlow<DeviceConnectionState>

    val connectionAlert: StateFlow<ConnectionAlert?>

    val shouldShowConnectPicker: StateFlow<Boolean>

    val consumeConnectPicker: () -> Unit

    val consumeCompanionPairingOnPicker: () -> Boolean

    val unreadCount: StateFlow<Int>

    /** The connected radio's name for the BLE menu header, or `null` when disconnected. */
    val localNodeName: StateFlow<String?>

    /** Whether the connected radio is running in client-repeat mode; `false` when disconnected. */
    val radioClientRepeat: StateFlow<Boolean>

    val permissions: PermissionController

    val bleScan: BleScanController

    val companionDevices: CompanionDeviceRegistry

    val actions: ConnectionActions

    /**
     * The live radio's [ContactService], or `null` when disconnected. Read live by the Nodes tab's
     * contact list; defaulted so a platform that has not yet wired the per-connection service compiles.
     */
    val contactService: () -> ContactService?

    /**
     * The live radio's [ConversationListStore], or `null` when disconnected. Read live by the Chats tab's
     * conversation list; defaulted so a platform that has not yet wired the per-connection store compiles.
     */
    val conversationListStore: () -> ConversationListStore?

    /**
     * Emits when the chats list should reload (ingest, block, contact delete). iOS SyncCoordinator
     * version-bump analogue. Default never emits.
     */
    val conversationListInvalidations: Flow<Unit>

    /**
     * Outbound DM/channel status events for the chats list's failed-send badges. Default never emits.
     */
    val messageStatusEvents: () -> Flow<MessageStatusEvent>

    /**
     * Room-server status events for the chats list's failed-send badges. Default never emits.
     */
    val roomServerEvents: () -> Flow<RoomServerEvent>

    /**
     * Reloads the conversation list after a room is marked read, matching iOS
     * `syncCoordinator.notifyConversationsChanged` on room open.
     */
    val onConversationsChanged: suspend () -> Unit

    /**
     * Emits when the Nodes list should reload after an app-side contact mutation (Add Contact, QR scan,
     * Discover "Add", node-config import), which carry no device push. iOS `contactsVersion` analogue.
     * Default never emits.
     */
    val contactListInvalidations: Flow<Unit>

    /**
     * Warms inline-image and link-preview dimensions for chats on the list. The iOS
     * `prewarmTopConversations` analogue. Default no-op.
     */
    val prewarmConversationPreviews: suspend (List<Conversation>) -> Unit

    /**
     * Speculatively loads page 0 of a closed chat before navigation. The iOS
     * `prefetchConversation` analogue. Default no-op.
     */
    val prefetchConversation: suspend (Conversation) -> Unit
}

/** Connection chrome and the conversation-list providers the shell reads. */
data class ShellSessionInputs(
    override val coordinator: NavigationCoordinator,
    override val onboarding: OnboardingViewModel,
    override val initialOnboardingComplete: Boolean,
    override val statusPill: StateFlow<StatusPillState>,
    override val battery: StateFlow<BatteryInfo?>,
    override val batteryOcvArray: StateFlow<List<Int>> = MutableStateFlow(OCVPreset.LI_ION.ocvArray),
    override val refreshBattery: suspend () -> Unit = {},
    override val connectionState: StateFlow<DeviceConnectionState>,
    override val connectionAlert: StateFlow<ConnectionAlert?>,
    override val shouldShowConnectPicker: StateFlow<Boolean> = MutableStateFlow(false),
    override val consumeConnectPicker: () -> Unit = {},
    override val consumeCompanionPairingOnPicker: () -> Boolean = { false },
    override val unreadCount: StateFlow<Int>,
    override val localNodeName: StateFlow<String?>,
    override val radioClientRepeat: StateFlow<Boolean> = MutableStateFlow(false),
    override val permissions: PermissionController,
    override val bleScan: BleScanController,
    override val companionDevices: CompanionDeviceRegistry = NoOpCompanionDeviceRegistry,
    override val actions: ConnectionActions,
    override val contactService: () -> ContactService? = { null },
    override val conversationListStore: () -> ConversationListStore? = { null },
    override val conversationListInvalidations: Flow<Unit> = emptyFlow(),
    override val messageStatusEvents: () -> Flow<MessageStatusEvent> = { emptyFlow() },
    override val roomServerEvents: () -> Flow<RoomServerEvent> = { emptyFlow() },
    override val onConversationsChanged: suspend () -> Unit = {},
    override val contactListInvalidations: Flow<Unit> = emptyFlow(),
    override val prewarmConversationPreviews: suspend (List<Conversation>) -> Unit = {},
    override val prefetchConversation: suspend (Conversation) -> Unit = {},
) : ShellSessionHost
