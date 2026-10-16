package one.meshcore.shared.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.navigateToRoom
import one.meshcore.shared.navigation.stageChannelLink
import one.meshcore.shared.navigation.stageContactLink
import one.meshcore.shared.navigation.stageHashtagLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dismissing a staged sheet has to release the state that staged it. A staged link is held in a
 * `StateFlow`, so leaving it set makes the same link un-openable for the rest of the session: the
 * re-tap writes an equal value, the flow conflates it, and the shell's staged-link effect never runs
 * again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StagedSheetTeardownTest {
    private val channelLink = MeshCoreDeepLink.Channel(name = "Ops", secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x11 })
    private val contactLink = MeshCoreDeepLink.Contact(
        name = "Alice",
        publicKey = ByteArray(PUBLIC_KEY_BYTES) { 0x22 },
        type = ContactType.CHAT,
    )

    @Test
    fun dismissingTheJoinFromLinkSheetReleasesTheStagedChannelLink() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        nav.stageChannelLink(channelLink)
        assertNotNull(nav.pendingChannelLink.value)

        nav.clearStagingFor(ShellSheet.JoinChannelLink(channelLink))

        assertNull(nav.pendingChannelLink.value)
    }

    @Test
    fun dismissingTheAddContactSheetReleasesTheStagedContactLink() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        nav.stageContactLink(contactLink)

        nav.clearStagingFor(ShellSheet.ConfirmAddContact(contactLink))

        assertNull(nav.pendingContactLink.value)
    }

    @Test
    fun dismissingTheHashtagSheetReleasesTheStagedHashtag() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        nav.stageHashtagLink("#ops")

        nav.clearStagingFor(ShellSheet.JoinHashtagFromMessage("#ops"))

        assertNull(nav.pendingHashtagJoin.value)
    }

    @Test
    fun dismissingTheRoomAuthSheetReleasesTheStagedSession() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        nav.navigateToRoom(sessionId = "room-1", isConnected = false)
        assertNotNull(nav.pendingRoomAuth.value)

        nav.clearStagingFor(ShellSheet.RoomAuth("room-1"))

        assertNull(nav.pendingRoomAuth.value)
    }

    @Test
    fun radioTeardownDismissesStagedSheetsAndKeepsConnect() {
        assertTrue(ShellSheet.ConfirmAddContact(contactLink).dismissOnRadioTeardown)
        assertTrue(ShellSheet.JoinChannelLink(channelLink).dismissOnRadioTeardown)
        assertTrue(ShellSheet.JoinHashtagFromMessage("#ops").dismissOnRadioTeardown)
        assertTrue(ShellSheet.RoomAuth("room-1").dismissOnRadioTeardown)
        assertTrue(ShellSheet.ShareMyContact.dismissOnRadioTeardown)
        assertFalse(ShellSheet.ConnectRadio.dismissOnRadioTeardown)
        assertFalse(ShellSheet.AddContact.dismissOnRadioTeardown)
        assertFalse(ShellSheet.ScanContact.dismissOnRadioTeardown)
        assertFalse(ShellSheet.ChannelOptions.dismissOnRadioTeardown)
    }

    @Test
    fun scanContactSystemBackReturnsToAddContact() {
        assertEquals(ShellSheet.AddContact, ShellSheet.ScanContact.backParent)
        assertNull(ShellSheet.AddContact.backParent)
        assertNull(ShellSheet.ChannelOptions.backParent)
    }

    @Test
    fun aSheetThatHoldsNoStagedStateLeavesAStagedLinkAlone() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        nav.stageChannelLink(channelLink)

        nav.clearStagingFor(ShellSheet.AddContact)

        assertNotNull(nav.pendingChannelLink.value)
    }

    @Test
    fun theSameLinkRestagesOnlyAfterTheDismissReleasedIt() = runTest(UnconfinedTestDispatcher()) {
        val nav = NavigationCoordinator(backgroundScope)
        var staged = 0
        // The shell opens a sheet for each non-null value the flow publishes. An equal value written
        // over an equal value is conflated, so a re-tap that was never released reaches no collector.
        backgroundScope.launch { nav.pendingChannelLink.collect { if (it != null) staged++ } }

        nav.stageChannelLink(channelLink)
        nav.stageChannelLink(channelLink)
        assertEquals(1, staged)

        nav.clearStagingFor(ShellSheet.JoinChannelLink(channelLink))
        nav.stageChannelLink(channelLink)
        assertEquals(2, staged)
    }

    private companion object {
        const val CHANNEL_SECRET_BYTES = 16
        const val PUBLIC_KEY_BYTES = 32
    }
}
