// AndroidOnly: WP-503 Actual offline Nodes entry, localized selection, primary text and native row routing at system font 200.
package com.meshcoreone.android.accessibility

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeNodesAccessibilityTest : NativeAccessibilityHarness() {
    private val radio = RadioId(UUID(0, 91))
    private val contact = ContactDTO(id = UUID(0, 92), radioId = radio, publicKey = Bytes(ByteArray(32) { 0xAA.toByte() }),
        name = "\u4e2d", lastHeardTimestamp = null, isBlocked = true)

    @Test fun offlineCachedNodeRetainsReadableNameTextualStatusAndNativeRowRouting() {
        var opened: ContactDTO? = null
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    NodesEntry(FeatureRoute(FeatureId.NODES), { error("No cross-feature route requested") },
                        dependencies = offlineDependencies(), navigation = object : NodesNavigation {
                            override fun openContact(contact: ContactDTO) { opened = contact }
                            override fun openDiscovery() = error("Unexpected discovery")
                            override fun openChat(contact: ContactDTO) = error("Unexpected chat")
                            override fun openMap(latitude: Double, longitude: Double) = error("Unexpected map")
                            override fun openRemoteNode(contact: ContactDTO, action: RemoteNodeAction) = error("Unexpected remote node")
                            override fun back() = error("Unexpected Back")
                        })
                }
            }
        }
        compose.onNodeWithText(host.get().getString(NodeSegment.CONTACTS.titleRes)).assertIsSelected()
        val label = compose.onNodeWithText(contact.name, useUnmergedTree = true).assertIsDisplayed()
        assertFont200Unclipped(label)
        val blocked = host.get().getString(C.contactsDetailBlocked)
        compose.onNodeWithText(blocked, substring = true).assertIsDisplayed()
        val row = compose.onNode(hasText(contact.name) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
            .assertIsDisplayed()
        assertVisibleClickTargetsAtLeast48Dp()
        row.performClick()
        assertEquals(contact, opened)
    }

    @Test fun offlineSyncShowsLocalizedRecoveryDialogWithReachableScaledTextAndDismissAction() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface { NodesEntry(FeatureRoute(FeatureId.NODES), { error("No route requested") }, offlineDependencies()) }
            }
        }
        val sync = host.get().getString(C.contactsListSyncNodes)
        compose.onNodeWithText(sync).performScrollTo().performClick()
        val message = host.get().getString(C.contactsListConnectToSync)
        val text = compose.onNodeWithText(message).assertIsDisplayed()
        assertFont200Unclipped(text)
        compose.onNodeWithText(host.get().getString(C.contactsCommonOk)).assertIsDisplayed().performClick()
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.onNodeWithText(contact.name).assertIsDisplayed()
    }

    private fun offlineDependencies(): NodesFeatureDependencies {
        val store = object : NodesDataStore {
            override suspend fun fetchContacts(radioId: RadioId) = listOf(contact).filter { it.radioId == radioId }
            override suspend fun fetchContact(key: EntityKey) = contact.takeIf { it.id == key.id }
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = contact.takeIf { it.radioId == radioId && it.publicKey == publicKey }
            override suspend fun fetchBlockedContacts(radioId: RadioId) = fetchContacts(radioId).filter { it.isBlocked }
            override suspend fun fetchContactPublicKeys(radioId: RadioId) = fetchContacts(radioId).map { it.publicKey }.toSet()
            override suspend fun fetchDiscoveredNodes(radioId: RadioId) = emptyList<com.meshcoreone.android.core.model.DiscoveredNodeDTO>()
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
            uriCodec = object : ContactUriCodec {
                override fun exportContactUri(name: String, publicKey: Bytes, type: ContactType): String = error("No export expected")
                override fun parseContactUri(text: String) = error("No parse expected")
            },
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
            clock = object : NodesClock {
                override val wallNow = Instant.ofEpochSecond(100)
                override val elapsed = Duration.ZERO
                override suspend fun sleep(duration: Duration) = error("No sleep expected")
            })
    }
}
