// AndroidOnly: WP-503 Share original offline discovery/path assertions and deterministic dependencies across JVM and Android runners.
package com.meshcoreone.android.accessibility

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.NodesDestination
import com.meshcoreone.android.feature.nodes.NodesEntry
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.RemoteNodeAction
import com.meshcoreone.android.feature.nodes.deps.Announcer
import com.meshcoreone.android.feature.nodes.deps.ContactUriCodec
import com.meshcoreone.android.feature.nodes.deps.FirmwareTimeouts
import com.meshcoreone.android.feature.nodes.deps.NodesClock
import com.meshcoreone.android.feature.nodes.deps.NodesDataStore
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesSession
import com.meshcoreone.android.feature.nodes.deps.StringListPreferences
import com.meshcoreone.android.feature.nodes.deps.UserFacingMessages
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import org.junit.Assert.assertEquals

internal class OfflineNodesAudit(
    private val compose: ComposeTestRule,
    private val activity: ComponentActivity,
    private val content: (@Composable () -> Unit) -> Unit,
    private val AuditSurface: @Composable (@Composable () -> Unit) -> Unit,
    private val assertFont200Unclipped: (SemanticsNodeInteraction) -> Unit,
    private val assertVisibleClickTargetsAtLeast48Dp: () -> Unit,
) {
    private var discoveredReads = 0
    private val radio = RadioId(UUID(0, 91))
    val contact = ContactDTO(id = UUID(0, 92), radioId = radio, publicKey = Bytes(ByteArray(32) { 0xAA.toByte() }),
        name = "\u4e2d", lastHeardTimestamp = null, isBlocked = true)

    fun offlinePathAndTransportActionsKeepDisabledStateAndFullLabelsAtFont200() {
        mountDetail(contact.copy(isBlocked = false))
        for (label in listOf(C.contactsDetailDiscoverPath, C.contactsDetailEditPath, C.contactsDetailResetPath,
            C.contactsDetailSendMessage, C.contactsDetailTelemetry, C.contactsDetailShareViaAdvert)) {
            val text = activity.getString(label)
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(text))
            val action = compose.onNodeWithText(text).performScrollTo()
            val measured = action.fetchSemanticsNode()
            println("WP503_OFFLINE_DETAIL_ACTION|sdk=${android.os.Build.VERSION.SDK_INT}|label=$text" +
                "|bounds=${measured.boundsInRoot}|size=${measured.size}|position=${measured.positionInRoot}" +
                "|touchBounds=${measured.touchBoundsInRoot}|root=${compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot}" +
                "|realRadio=false")
            action.assertIsDisplayed().assertIsNotEnabled()
            assertFont200Unclipped(action)
        }
        val history = activity.getString(C.contactsDetailSavedHistory)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(history))
        assertFont200Unclipped(compose.onNodeWithText(history).performScrollTo().assertIsDisplayed().assertIsEnabled())
        assertVisibleClickTargetsAtLeast48Dp()
    }

    fun offlineDiscoveryHasATextualCachedOutcomeInsteadOfAnUnownedIndefiniteSpinner() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    NodesEntry(FeatureRoute(FeatureId.NODES), { error("No route requested") },
                        offlineDependencies(), destination = NodesDestination.Discovery)
                }
            }
        }
        compose.waitForIdle()
        val pending = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes()
        println("WP503_OFFLINE_DISCOVERY|sdk=${android.os.Build.VERSION.SDK_INT}|indeterminate=${pending.size}|discoveredReads=$discoveredReads" +
            "|cachedRadio=true|storeAvailable=true|connectedDevice=false|realRadio=false")
        assertEquals("Offline discovery has no active transport/load; expose a textual cached/empty or recovery outcome",
            0, pending.size)
        assertEquals(1, discoveredReads)
        assertFont200Unclipped(compose.onNodeWithText(activity.getString(C.contactsDiscoveryEmptyTitle)).assertIsDisplayed())
        assertFont200Unclipped(compose.onNodeWithText(activity.getString(C.contactsDiscoveryEmptyDescription)).assertIsDisplayed())
        assertVisibleClickTargetsAtLeast48Dp()
    }

    fun mountDetail(
        cachedContact: ContactDTO,
        onBack: () -> Unit = { error("Unexpected Back") },
        dependencies: NodesFeatureDependencies = offlineDependencies(cachedContact),
    ) {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    NodesEntry(FeatureRoute(FeatureId.NODES), { error("No cross-feature route expected") },
                        dependencies = dependencies, destination = NodesDestination.Detail(cachedContact),
                        navigation = object : NodesNavigation {
                            override fun openContact(contact: ContactDTO) = error("Unexpected contact")
                            override fun openDiscovery() = error("Unexpected discovery")
                            override fun openChat(contact: ContactDTO) = error("Unexpected chat")
                            override fun openMap(latitude: Double, longitude: Double) = error("Unexpected map")
                            override fun openRemoteNode(contact: ContactDTO, action: RemoteNodeAction) = error("Unexpected remote node")
                            override fun back() = onBack()
                        })
                }
            }
        }
    }

    fun offlineDependencies(
        cachedContact: ContactDTO = contact,
        cachedContacts: List<ContactDTO> = listOf(cachedContact),
        contactUriCodec: ContactUriCodec = object : ContactUriCodec {
            override fun exportContactUri(name: String, publicKey: Bytes, type: ContactType): String = error("No export expected")
            override fun parseContactUri(text: String) = error("No parse expected")
        },
        clock: NodesClock = object : NodesClock {
            override val wallNow = Instant.ofEpochSecond(100)
            override val elapsed = Duration.ZERO
            override suspend fun sleep(duration: Duration) = error("No sleep expected")
        },
    ): NodesFeatureDependencies {
        val store = object : NodesDataStore {
            override suspend fun fetchContacts(radioId: RadioId) = cachedContacts.filter { it.radioId == radioId }
            override suspend fun fetchContact(key: EntityKey) = cachedContacts.firstOrNull { it.radioId == key.radioId && it.id == key.id }
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = cachedContacts.firstOrNull { it.radioId == radioId && it.publicKey == publicKey }
            override suspend fun fetchBlockedContacts(radioId: RadioId) = fetchContacts(radioId).filter { it.isBlocked }
            override suspend fun fetchContactPublicKeys(radioId: RadioId) = fetchContacts(radioId).map { it.publicKey }.toSet()
            override suspend fun fetchDiscoveredNodes(radioId: RadioId): List<com.meshcoreone.android.core.model.DiscoveredNodeDTO> {
                assertEquals(radio, radioId)
                discoveredReads++
                return emptyList()
            }
            override suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant): Boolean = error("No touch expected")
            override suspend fun deleteDiscoveredNode(key: EntityKey) = error("No delete expected")
            override suspend fun clearDiscoveredNodes(radioId: RadioId) = error("No clear expected")
        }
        val session = object : NodesSession {
            override fun connectionState() = DeviceConnectionState.DISCONNECTED
            override fun currentRadioId() = radio
            override fun offlineDataStore() = store
            override fun connectedDevice() = null
            override fun servicesDataStore() = null
            override fun contactService() = null
            override fun advertisementService() = null
            override fun traceService() = null
            override fun notificationCleanup() = null
        }
        return NodesFeatureDependencies(session,
            uriCodec = contactUriCodec,
            timeouts = object : FirmwareTimeouts {
                override fun pathDiscoverySeconds(suggestedTimeoutMs: UInt): Double = error("No discovery timeout expected")
                override fun pathDiscoveryRetransmitInterval(suggestedTimeoutMs: UInt): Duration? = error("No retransmit expected")
                override fun zeroHopSeconds(suggestedTimeoutMs: UInt): Double = error("No ping expected")
            },
            messages = UserFacingMessages { error("No transport exception expected: ${it.javaClass.simpleName}") },
            announcer = Announcer { error("No announcement expected") },
            preferences = object : StringListPreferences {
                override fun stringList(key: String): List<String>? = error("No preferences read expected")
                override fun setStringList(key: String, value: List<String>) = error("No preferences write expected")
            },
            clock = clock)
    }
}
