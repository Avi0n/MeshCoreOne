package one.meshcore.shared.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.FakeConversationListStore
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.StatusPillState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.SendAdvertShortcutOutcome
import one.meshcore.shared.onboarding.OnboardingViewModel
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A hashtag tap in a message looks up the radio's channels and either opens the match or stages the
 * join sheet. A channel-store fault still stages the join. Cancellation and an error do not.
 */
class OpenHashtagLinkTest {
    @Test
    fun aJoinedChannelOpensWithoutASheet() = runTest {
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val channelId = Uuid.normalized("C0000000-0000-0000-0000-000000000003")
        val store = FakeConversationListStore().apply {
            channels = listOf(channel(id = channelId, radioID = radio, name = "#weather"))
        }
        val deps = shell(store, radio)
        val sheet = openHashtagLink(LinkTarget.Hashtag("weather"), deps)
        assertNull(sheet)
        assertEquals(ChatRoute.Channel(channelId.value), deps.coordinator.chatsSelectedRoute.value)
    }

    @Test
    fun aFailedChannelFetchStagesTheJoinSheet() = runTest {
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val store = FakeConversationListStore().apply {
            fetchChannelsFailure = IllegalStateException("store unavailable")
        }
        val deps = shell(store, radio)
        val sheet = openHashtagLink(LinkTarget.Hashtag("weather"), deps)
        assertEquals(ShellSheet.JoinHashtagFromMessage("#weather"), sheet)
        assertNull(deps.coordinator.chatsSelectedRoute.value)
    }

    @Test
    fun aCancelledChannelFetchDoesNotStageTheJoin() = runTest {
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val cancelled = CancellationException("stopped")
        val store = FakeConversationListStore().apply {
            fetchChannelsFailure = cancelled
        }
        val deps = shell(store, radio)
        val thrown = assertFailsWith<CancellationException> {
            openHashtagLink(LinkTarget.Hashtag("weather"), deps)
        }
        assertSame(cancelled, thrown)
        assertNull(deps.coordinator.chatsSelectedRoute.value)
    }

    @Test
    fun aChannelFetchErrorDoesNotStageTheJoin() = runTest {
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val probe = HashtagOpenProbeError()
        val store = FakeConversationListStore().apply {
            fetchChannelsFailure = probe
        }
        val deps = shell(store, radio)
        val thrown = assertFailsWith<HashtagOpenProbeError> {
            openHashtagLink(LinkTarget.Hashtag("weather"), deps)
        }
        assertSame(probe, thrown)
        assertNull(deps.coordinator.chatsSelectedRoute.value)
    }

    @Test
    fun noRadioStagesTheJoinWithoutFetching() = runTest {
        val store = FakeConversationListStore().apply {
            fetchChannelsFailure = HashtagOpenProbeError()
        }
        val sheet = openHashtagLink(LinkTarget.Hashtag("weather"), shell(store, radioId = null))
        assertEquals(ShellSheet.JoinHashtagFromMessage("#weather"), sheet)
    }

    private fun TestScope.shell(store: ConversationListStore, radioId: Uuid?): ShellDependencies = ShellDependencies(
        session = ShellSessionInputs(
            coordinator = NavigationCoordinator(backgroundScope),
            onboarding = OnboardingViewModel(InMemoryPreferencesStore()),
            initialOnboardingComplete = true,
            statusPill = MutableStateFlow(StatusPillState.Hidden),
            battery = MutableStateFlow(null),
            connectionState = MutableStateFlow(DeviceConnectionState.READY),
            connectionAlert = MutableStateFlow(null),
            unreadCount = MutableStateFlow(0),
            localNodeName = MutableStateFlow(null),
            permissions = IdlePermissionController,
            bleScan = IdleBleScanController,
            actions = IdleConnectionActions,
            conversationListStore = { store },
        ),
        services = ShellServiceInputs(currentRadioId = { radioId }),
        sharing = ShellSharingInputs(),
        preferences = ShellPreferenceInputs(),
        radio = ShellRadioInputs(
            settingsStartupReadsAllowed = MutableStateFlow(false),
            refreshDeviceSettings = {},
        ),
        maintenance = ShellMaintenanceInputs(),
    )

    private fun channel(id: Uuid, radioID: Uuid, name: String): ChannelDto = ChannelDto(
        id = id,
        radioID = radioID,
        index = 0,
        name = name,
        secret = ByteArray(ChannelSecret.SIZE_BYTES),
        isEnabled = true,
        unreadCount = 0,
    )
}

private class HashtagOpenProbeError : Error("probe")

private object IdlePermissionController : PermissionController {
    override val state = MutableStateFlow(PermissionUiState())

    override fun request(permission: OnboardingPermission) = Unit

    override fun refresh() = Unit
}

private object IdleBleScanController : BleScanController {
    override val availability = MutableStateFlow(BleAvailability.READY)
    override val devices = MutableStateFlow(emptyList<ScannedDevice>())

    override fun startScan() = Unit

    override fun stopScan() = Unit
}

private object IdleConnectionActions : ConnectionActions {
    override suspend fun connect(deviceId: String) = Unit

    override suspend fun connectViaWiFi(host: String, port: Int) = Unit

    override suspend fun connectSimulator() = Unit

    override suspend fun disconnect() = Unit

    override suspend fun sendAdvert(flood: Boolean): SendAdvertShortcutOutcome = SendAdvertShortcutOutcome.NotConnected

    override fun dismissAlert() = Unit
}
