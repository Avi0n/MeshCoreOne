// AndroidOnly: WP-503 Execute original mounted messaging assertions and real-App fallback disclosure on the actual selected API.
package com.meshcoreone.android.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.EntityKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.actions.NodeNameMatchKind
import com.meshcoreone.android.feature.chats.actions.PathNodeResolver
import com.meshcoreone.android.feature.onboarding.OnboardingState
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMessagingFlowAccessibilityTest : DeviceAccessibilityHarness() {
    @get:Rule val fallbackErrors = ErrorCollector()

    private val audit by lazy {
        MessagingAccessibilityAudit(compose, Supplier { activity }, ::content, { AuditSurface(it) })
    }

    @Test fun roomFollowUpRetainsAuthorContextWithoutAnnouncingTheWholeChat() =
        audit.roomFollowUpRetainsAuthorContextWithoutAnnouncingTheWholeChat()

    @Test fun roomOutgoingSentStatusIsExposedExactlyOnceOnItsMessage() =
        audit.roomOutgoingSentStatusIsExposedExactlyOnceOnItsMessage()

    @Test fun roomClusterStartAnnouncesItsAuthorExactlyOnce() =
        audit.roomClusterStartAnnouncesItsAuthorExactlyOnce()

    @Test fun roomSentMessageHasNoRetryAction() =
        audit.roomSentMessageHasNoRetryAction()

    @Test fun roomFailedRetryHasANativeTargetAndInvokesTheCorrectMessageOnly() =
        audit.roomFailedRetryHasANativeTargetAndInvokesTheCorrectMessageOnly()

    @Test fun roomHttpsMessageExposesANamedNonGestureLinkAction() =
        audit.roomHttpsMessageExposesANamedNonGestureLinkAction()

    @Test fun rememberPasswordSwitchHasPurposeAndCheckedStateOnOneNode() =
        audit.rememberPasswordSwitchHasPurposeAndCheckedStateOnOneNode()

    @Test fun directFailureRetryAndMixedDirectionTextHonorSystemFontAndTargets() =
        audit.directFailureRetryAndMixedDirectionTextHonorSystemFontAndTargets()

    @Test fun auditDetectsLiveRegionOnTheActualTimelineInsteadOfOnlyTheTestSurface() =
        audit.auditDetectsLiveRegionOnTheActualTimelineInsteadOfOnlyTheTestSurface()

    @Test fun auditAllowsASmallFailureOnlyLiveRegionWithoutAnnouncingOtherRows() =
        audit.auditAllowsASmallFailureOnlyLiveRegionWithoutAnnouncingOtherRows()

    // OriginalCase: MC1Tests/Views/Chats/Components/UnifiedMessageBubbleA11yLabelTests.swift@db14559b39d32322b06477c6ae676112f583db50
    // OriginalInputs: MC1Tests/Views/Chats/Components/MessageBubbleTestData.swift@db14559b39d32322b06477c6ae676112f583db50
    @Test fun realAppFallbackSenderLabelIncludesPossibleMatchDisclosure() {
        val application = requireNotNull(activity.application as? MeshCoreApplication)
        val container = runBlocking { application.container.await() }
        val store = container.processStore
        val onboarding = requireNotNull(container.onboarding)
        val preferences = MeshCoreStorage.get(activity).preferences
        val preferenceKey = AppStorageKey.hasCompletedOnboarding
        val originalPreferences = runBlocking { preferences.snapshot() }
        val originalCompleted = onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED)
        val originalNavigation = container.navigation.state.value
        val originalActiveDevice = runBlocking { store.fetchActiveDevice() }
        val radio = RadioId(UUID(0, 503601))
        val device = DeviceDTO(id = radio.value, radioId = radio,
            publicKey = Bytes(ByteArray(32) { 0x60 }), nodeName = "Fallback disclosure fixture")
        val channel = ChannelDTO(id = UUID(0, 503602), radioId = radio, index = 1u,
            name = "Fallback disclosure")
        val alice = ContactDTO(id = UUID(0, 503603), radioId = radio,
            publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }.also { it[31] = 3 }),
            name = "Alice", lastHeardTimestamp = null)
        val zulu = alice.copy(id = UUID(0, 503604),
            publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }.also { it[31] = 4 }), name = "Zulu")
        val date = Instant.ofEpochSecond(1_700_000_000)
        val message = MessageDTO(id = UUID(0, 503605), radioId = radio, channelIndex = channel.index,
            text = "hi", timestamp = date.epochSecond.toUInt(), createdAt = date,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            pathLength = 3u, pathNodes = Bytes.of(0xA3, 0x7F, 0x42),
            senderKeyPrefix = Bytes.of(0xAB), senderNodeName = null, isRead = true)
        val messageKey = EntityKey(radio, message.id)
        var seeded = false
        var changedOnboarding = false
        var enteredChannel = false
        var channelEntryId: Long? = null
        var navigationRestored = true
        var preferencesRestored = true
        var onboardingWriteObserved = false
        try {
            // Collect the original assertion failure separately from any independent cleanup failure.
            fallbackErrors.checkSucceeds {
                assertEquals(originalPreferences[preferenceKey], originalCompleted)
                assertEquals(AppTab.CHATS, originalNavigation.selectedTab)
                assertNull(originalNavigation.pendingChatContact)
                assertNull(originalNavigation.pendingChannel)
                assertNull(originalNavigation.pendingRoomSession)
                assertNull(originalNavigation.pendingScrollToMessageID)
                assertNull(originalNavigation.failure)
                runBlocking {
                    assertNull("Never overwrite an existing fixture device", store.fetchDevice(device.id))
                    assertNull("Never reuse an existing radio namespace", store.fetchDevice(radio))
                    assertNull("Never reuse an existing device key", store.fetchDevice(device.publicKey))
                    assertTrue(store.fetchContacts(radio).isEmpty())
                    assertTrue(store.fetchChannels(radio).isEmpty())
                    assertTrue(store.fetchMessages(radio, channel.index).isEmpty())
                    assertNull(store.fetchMessage(messageKey))
                    seeded = true
                    store.saveDevice(device)
                    store.saveChannel(channel)
                    store.saveContact(alice)
                    store.saveContact(zulu)
                    store.saveMessage(message)
                }
                compose.runOnUiThread {
                    assertEquals(originalNavigation.stacks, container.navigation.state.value.stacks)
                    changedOnboarding = true
                    preferencesRestored = false
                    onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, true)
                    enteredChannel = true
                    navigationRestored = false
                    container.navigation.navigateToChannel(channel)
                    channelEntryId = container.navigation.state.value.activeStack.last().id
                }
                compose.waitUntil { runBlocking { preferences.get(preferenceKey) } }
                onboardingWriteObserved = true
                val rowMatcher = SemanticsMatcher("Actual merged App message label resolves Alice") {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()
                        ?.startsWith("Alice: ") == true
                }
                compose.waitUntil { compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size == 1 }
                val row = compose.onNode(rowMatcher).assertIsDisplayed().fetchSemanticsNode()
                val body = compose.onNodeWithText(message.text, useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode()
                assertTrue("The label must belong to the real persisted message row, not a synthetic label node",
                    generateSequence(body.parent) { it.parent }.any { it.id == row.id })
                assertTrue(row.config.isMergingSemanticsOfDescendants)
                val label = row.config[SemanticsProperties.ContentDescription].single()
                val disclosure = activity.getString(AppChatsStrings.chatsMessageSenderPossibleMatch)
                assertTrue(label.startsWith("Alice: "))
                assertTrue(label.contains(disclosure))
                runBlocking {
                    assertEquals(message, store.fetchMessage(messageKey))
                    val resolution = PathNodeResolver.resolution(message.senderKeyPrefix,
                        store.fetchContacts(radio), emptyList(), null,
                        activity.getString(AppChatsStrings.chatsMessageSenderUnknown))
                    assertEquals("Alice", resolution.displayName)
                    assertEquals(NodeNameMatchKind.FALLBACK, resolution.matchKind)
                    assertEquals(originalActiveDevice, store.fetchActiveDevice())
                }
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                var nativeMessage: AccessibilityNodeInfo? = null
                compose.waitUntil {
                    val window = automation.rootInActiveWindow
                    if (window == null || window.packageName?.toString() != activity.packageName) false
                    else {
                        val matches = window.findAccessibilityNodeInfosByText(label).filter {
                            it.contentDescription?.toString() == label && it.isVisibleToUser
                        }
                        assertTrue("Only one actual native message may expose the observed label", matches.size <= 1)
                        nativeMessage = matches.singleOrNull()
                        nativeMessage != null
                    }
                }
                val native = requireNotNull(nativeMessage)
                assertTrue(native.refresh())
                assertEquals(label, native.contentDescription?.toString())
                val bounds = Rect().also(native::getBoundsInScreen)
                assertTrue("The exposed native message must have actual visible bounds", !bounds.isEmpty)
                println("WP503_NATIVE_FALLBACK_DISCLOSURE|sdk=${android.os.Build.VERSION.SDK_INT}" +
                    "|message=${message.id}|composeRow=${row.id}|bodyNode=${body.id}|nativeBounds=$bounds" +
                    "|label=$label|resolution=Alice/FALLBACK|realStore=true|realAppGraph=true|physicalTalkBack=false")
            }
        } finally {
            if (enteredChannel) fallbackErrors.checkSucceeds {
                compose.runOnUiThread {
                    assertEquals(requireNotNull(channelEntryId) { "The owned navigation entry was not observed" },
                        container.navigation.state.value.activeStack.last().id)
                    assertEquals(NavigationDestination.Chat(ChatSelection.Channel(channel)),
                        container.navigation.state.value.activeStack.last().destination)
                    assertTrue(container.navigation.back())
                    assertEquals(originalNavigation.stacks, container.navigation.state.value.stacks)
                    assertEquals(originalNavigation.chatsSelectedRoute, container.navigation.state.value.chatsSelectedRoute)
                    assertEquals(originalNavigation.selectedTab, container.navigation.state.value.selectedTab)
                }
                compose.waitForIdle()
                navigationRestored = true
            }
            if (changedOnboarding && !onboardingWriteObserved) fallbackErrors.checkSucceeds {
                compose.waitUntil { runBlocking { preferences.get(preferenceKey) } }
                onboardingWriteObserved = true
            }
            if (changedOnboarding) fallbackErrors.checkSucceeds {
                compose.runOnUiThread { onboarding.flags.setBoolean(OnboardingState.KEY_HAS_COMPLETED, originalCompleted) }
                assertTrue("Do not remove a preference while its initial queued write is uncertain", onboardingWriteObserved)
                compose.waitUntil { runBlocking { preferences.get(preferenceKey) } == originalCompleted }
                if (!originalPreferences.contains(preferenceKey)) runBlocking { preferences.remove(preferenceKey) }
                assertEquals(originalPreferences.contains(preferenceKey),
                    runBlocking { preferences.snapshot().contains(preferenceKey) })
                assertEquals(originalCompleted, runBlocking { preferences.get(preferenceKey) })
                assertEquals(originalCompleted, onboarding.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED))
                preferencesRestored = true
            }
            if (seeded) fallbackErrors.checkSucceeds {
                assertTrue("Keep persisted fixture rows if navigation or preference cleanup is uncertain",
                    navigationRestored && preferencesRestored)
                runBlocking {
                    assertTrue(store.fetchContacts(radio).all { it.id == alice.id || it.id == zulu.id })
                    assertTrue(store.fetchChannels(radio).all { it.id == channel.id })
                    assertTrue(store.fetchMessages(radio, channel.index).all { it.id == message.id })
                    store.deleteMessage(messageKey)
                    store.deleteChannel(EntityKey(radio, channel.id))
                    store.deleteContact(EntityKey(radio, alice.id))
                    store.deleteContact(EntityKey(radio, zulu.id))
                    store.deleteDevice(device.id)
                    assertNull(store.fetchMessage(messageKey))
                    assertNull(store.fetchDevice(device.id))
                    assertTrue(store.fetchContacts(radio).isEmpty())
                    assertTrue(store.fetchChannels(radio).isEmpty())
                    assertEquals(originalActiveDevice, store.fetchActiveDevice())
                }
            }
        }
    }
}
