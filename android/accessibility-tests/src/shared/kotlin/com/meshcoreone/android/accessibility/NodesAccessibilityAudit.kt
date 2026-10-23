// AndroidOnly: WP-503 Share original Nodes assertions and deterministic ports across JVM and actual Android runners.
package com.meshcoreone.android.accessibility

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.feature.nodes.NodesEntry
import com.meshcoreone.android.feature.nodes.NodesDestination
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.RemoteNodeAction
import com.meshcoreone.android.feature.nodes.deps.ContactUriCodec
import com.meshcoreone.android.feature.nodes.deps.NodesClock
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class NodesAccessibilityAudit(
    override val compose: ComposeTestRule,
    private val host: Supplier<ComponentActivity>,
    private val render: (@Composable () -> Unit) -> Unit,
    private val surface: @Composable (@Composable () -> Unit) -> Unit,
    private val startedActivity: Supplier<Intent?>,
) : NativeAccessibilityAssertions() {
    override val activity: ComponentActivity get() = host.get()

    override fun content(body: @Composable () -> Unit) = render(body)

    @Composable
    override fun AuditSurface(body: @Composable () -> Unit) = surface(body)

    private val audit by lazy {
        OfflineNodesAudit(compose, activity, ::content, { body -> AuditSurface(body) },
            ::assertFont200Unclipped, ::assertVisibleClickTargetsAtLeast48Dp)
    }
    private val contact get() = audit.contact

    fun offlineChatRepeaterAndRoomKeepTransportDisabledAndSavedHistoryRoutingReachable() {
        val contacts = ContactType.entries.mapIndexed { index, type ->
            contact.copy(id = UUID(0, 110L + index), typeRawValue = type.rawValue, isBlocked = false,
                name = "\u4e2d ${type.name}", publicKey = Bytes(ByteArray(32) { (0xA1 + index).toByte() }))
        }
        val selected = mutableStateOf(contacts.first())
        val dependencies = offlineDependencies(cachedContacts = contacts)
        val opened = mutableListOf<ContactDTO>()
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    NodesEntry(FeatureRoute(FeatureId.NODES), { error("No cross-feature route expected") },
                        dependencies = dependencies, destination = NodesDestination.Detail(selected.value),
                        navigation = object : NodesNavigation {
                            override fun openContact(contact: ContactDTO) = error("Unexpected contact")
                            override fun openDiscovery() = error("Unexpected discovery")
                            override fun openChat(contact: ContactDTO) = error("Offline messaging must remain disabled")
                            override fun openMap(latitude: Double, longitude: Double) = error("No location requested")
                            override fun openRemoteNode(contact: ContactDTO, action: RemoteNodeAction) {
                                assertEquals(RemoteNodeAction.SAVED_HISTORY, action)
                                opened += contact
                            }
                            override fun back() = error("No Back requested")
                        })
                }
            }
        }
        for (cached in contacts) {
            compose.runOnIdle { selected.value = cached }
            val disabled = buildList {
                add(C.contactsDetailTelemetry)
                add(C.contactsDetailShareViaAdvert)
                when (cached.type) {
                    ContactType.CHAT -> add(C.contactsDetailSendMessage)
                    ContactType.REPEATER -> addAll(listOf(C.contactsDetailManagement, C.contactsDetailPing))
                    ContactType.ROOM -> addAll(listOf(C.contactsDetailManagement, C.contactsDetailPing, C.contactsDetailJoinRoom))
                }
            }
            for (label in disabled) {
                val text = activity.getString(label)
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(text))
                assertFont200Unclipped(compose.onNodeWithText(text).performScrollTo().assertIsDisplayed().assertIsNotEnabled())
            }
            val historyText = activity.getString(C.contactsDetailSavedHistory)
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(historyText))
            val history = compose.onNodeWithText(historyText).performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertFont200Unclipped(history)
            assertVisibleClickTargetsAtLeast48Dp()
            history.performClick()
            assertEquals(cached, opened.last())
            println("WP503_OFFLINE_ROLE_ACTIONS|sdk=${android.os.Build.VERSION.SDK_INT}|type=${cached.type}" +
                "|disabled=${disabled.size}|historyCallback=true|realRadio=false")
        }
        assertEquals(contacts, opened)
    }

    fun offlinePathAndTransportActionsKeepDisabledStateAndFullLabelsAtFont200() =
        audit.offlinePathAndTransportActionsKeepDisabledStateAndFullLabelsAtFont200()

    fun offlineQrShareKeepsNamedAlternativeCopyFeedbackAndExactNativeIntentReachable() {
        var exports = 0
        var waits = 0
        val reset = CompletableDeferred<Unit>()
        var exported = ""
        val dependencies = offlineDependencies(contactUriCodec = object : ContactUriCodec {
            override fun exportContactUri(name: String, publicKey: Bytes, type: ContactType): String {
                assertEquals(contact.name, name)
                assertEquals(contact.publicKey, publicKey)
                assertEquals(ContactType.CHAT, type)
                exports++
                return ContactService.exportContactURI(name, publicKey, type).also { exported = it }
            }
            override fun parseContactUri(text: String) = error("Sharing must not import a contact")
        }, clock = object : NodesClock {
            override val wallNow = Instant.ofEpochSecond(100)
            override val elapsed = Duration.ZERO
            override suspend fun sleep(duration: Duration) {
                assertEquals(2.seconds, duration)
                waits++
                reset.await()
            }
        })
        mountDetail(contact, dependencies = dependencies)
        val shareLabel = activity.getString(C.contactsDetailShareContact)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(
            hasText(shareLabel) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        compose.onNode(hasText(shareLabel) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performScrollTo().performClick()
        compose.waitUntil { exports == 1 }
        assertEquals("meshcore://contact/add?name=%E4%B8%AD&public_key=${"AA".repeat(32)}&type=1", exported)
        val dialog = hasAnyAncestor(isDialog())
        compose.onNodeWithContentDescription(contact.displayName).assertIsDisplayed()
        assertFont200Unclipped(compose.onNode(hasText(List(32) { "AA" }.joinToString(" ")) and dialog))
        val copy = compose.onNodeWithText(activity.getString(C.contactsQrCopy)).assertIsDisplayed().assertIsEnabled()
        assertFont200Unclipped(copy)
        assertVisibleClickTargetsAtLeast48Dp()
        copy.performClick()
        compose.waitUntil { waits == 1 }
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.waitUntil(timeoutMillis = 5_000) {
            val clip = clipboard.primaryClip
            clip != null && clip.description.label == contact.displayName &&
                clip.itemCount > 0 && clip.getItemAt(0).text?.toString() == "AA".repeat(32)
        }
        val copiedClip = clipboard.primaryClip
        assertEquals(contact.displayName, copiedClip?.description?.label)
        assertEquals("AA".repeat(32), copiedClip?.getItemAt(0)?.text?.toString())
        val copied = compose.onNodeWithText(activity.getString(C.contactsQrCopied)).assertIsDisplayed().assertIsNotEnabled()
        assertFont200Unclipped(copied)
        compose.runOnIdle { reset.complete(Unit) }
        copy.assertIsDisplayed().assertIsEnabled()
        assertEquals(1, waits)
        compose.onNode(hasText(shareLabel) and dialog).assertIsDisplayed().performClick()
        val chooser = requireNotNull(startedActivity.get())
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(contact.displayName, chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())
        @Suppress("DEPRECATION")
        val payload = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, payload.action)
        assertEquals("text/plain", payload.type)
        assertEquals(exported, payload.getStringExtra(Intent.EXTRA_TEXT))
        val shareSubject = activity.getString(C.contactsQrShareSubject)
        assertTrue("Fixture must distinguish the localized subject from the contact name",
            shareSubject != contact.displayName)
        assertEquals(shareSubject, payload.getStringExtra(Intent.EXTRA_SUBJECT))
        val done = compose.onNodeWithText(activity.getString(C.contactsCommonDone)).assertIsDisplayed()
        assertFont200Unclipped(done)
        assertVisibleClickTargetsAtLeast48Dp()
        done.performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals(1, exports)
        println("WP503_NATIVE_QR_SHARE|sdk=${android.os.Build.VERSION.SDK_INT}|uriExports=$exports|copyWaits=$waits" +
            "|copiedKeyBytes=32|chooserIntentCaptured=true|realChooser=false|realRadio=false")
    }

    fun actualSearchCrossesSelectedSegmentsAndNativeNameSortKeepsScaledResultsReachable() {
        val favorite = contact.copy(name = "\u4e2dQ", isBlocked = false, isFavorite = true)
        val repeater = contact.copy(id = UUID(0, 93), name = "BQ", isBlocked = false,
            publicKey = Bytes(ByteArray(32) { 0xBB.toByte() }), typeRawValue = ContactType.REPEATER.rawValue)
        val room = contact.copy(id = UUID(0, 94), name = "AQ", isBlocked = false,
            publicKey = Bytes(ByteArray(32) { 0xCC.toByte() }), typeRawValue = ContactType.ROOM.rawValue)
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    NodesEntry(FeatureRoute(FeatureId.NODES), { error("No cross-feature route requested") },
                        offlineDependencies(cachedContacts = listOf(favorite, repeater, room)))
                }
            }
        }
        val favorites = compose.onNodeWithText(activity.getString(NodeSegment.FAVORITES.titleRes))
        favorites.assertIsSelected()
        assertFont200Unclipped(compose.onNodeWithText(favorite.name))
        val repeaters = compose.onNodeWithText(activity.getString(NodeSegment.REPEATERS.titleRes)).performScrollTo()
        assertFont200Unclipped(repeaters)
        repeaters.assertIsNotSelected().performClick().assertIsSelected()
        assertFont200Unclipped(compose.onNodeWithText(repeater.name))
        compose.onNodeWithText(favorite.name).assertDoesNotExist()
        val rooms = compose.onNodeWithText(activity.getString(NodeSegment.ROOMS.titleRes)).performScrollTo()
        assertFont200Unclipped(rooms)
        rooms.performClick().assertIsSelected()
        assertFont200Unclipped(compose.onNodeWithText(room.name))
        val search = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        search.performTextReplacement("BQ")
        rooms.assertIsSelected()
        assertFont200Unclipped(compose.onNode(hasText(repeater.name) and
            !SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)))
        compose.onNodeWithText(room.name).assertDoesNotExist()
        search.performTextReplacement("\u4e0d\u5b58\u5728")
        val empty = compose.onNode(hasText(activity.getString(C.contactsListEmptySearchTitle)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        assertFont200Unclipped(empty)
        search.performTextReplacement("Q")
        val sort = compose.onNodeWithText(activity.getString(C.contactsListSort)).performScrollTo()
        assertFont200Unclipped(sort)
        sort.performClick()
        val name = compose.onNodeWithText(activity.getString(NodeSortOrder.NAME.titleRes)).assertIsDisplayed()
        assertFont200Unclipped(name)
        assertVisibleClickTargetsAtLeast48Dp()
        name.performClick()
        val first = compose.onNodeWithText(room.name).performScrollTo().assertIsDisplayed()
        val second = compose.onNodeWithText(repeater.name).assertIsDisplayed()
        assertTrue("Actual native rows follow the selected name sort, not the old selected segment",
            first.fetchSemanticsNode().boundsInRoot.top < second.fetchSemanticsNode().boundsInRoot.top)
        assertFont200Unclipped(first)
        assertFont200Unclipped(second)
        val target = hasText(favorite.name)
        val before = compose.onAllNodes(target).fetchSemanticsNodes().size
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        list.performScrollToNode(target)
        assertFont200Unclipped(compose.onNodeWithText(favorite.name).performScrollTo())
        val measured = compose.onNodeWithText(favorite.name).fetchSemanticsNode()
        val bounds = measured.boundsInRoot
        val viewport = list.fetchSemanticsNode().boundsInRoot
        assertEquals(measured.size.height.toFloat(), bounds.height, 0f)
        assertEquals(measured.size.width.toFloat(), bounds.width, 0f)
        assertTrue("The complete scaled name must fit the actual native lazy viewport after public scroll",
            bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        println("WP503_NODES_LAZY_TRAVERSAL|sdk=${android.os.Build.VERSION.SDK_INT}|beforeComposed=$before" +
            "|afterComposed=1|bounds=$bounds|allocation=${measured.size}|viewport=$viewport")
        search.performTextReplacement("")
        rooms.performScrollTo().assertIsSelected()
        assertFont200Unclipped(compose.onNodeWithText(room.name))
        compose.onNodeWithText(repeater.name).assertDoesNotExist()
        assertVisibleClickTargetsAtLeast48Dp()
        println("WP503_NODES_FILTER_SORT|sdk=${android.os.Build.VERSION.SDK_INT}|autoFavorite=true" +
            "|searchCrossesSegments=true|nameSort=true|clearingRetainsRoom=true|realRadio=false")
    }

    fun offlineDiscoveryHasATextualCachedOutcomeInsteadOfAnUnownedIndefiniteSpinner() =
        audit.offlineDiscoveryHasATextualCachedOutcomeInsteadOfAnUnownedIndefiniteSpinner()

    fun offlineCachedNodeRetainsReadableNameTextualStatusAndNativeRowRouting() {
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

    fun offlineSyncShowsLocalizedRecoveryDialogWithReachableScaledTextAndDismissAction() {
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

    fun actualDetailKeepsALongCjkHeadingAndNicknameCancellationReachableAtFont200() {
        val longName = "\u4e2d\u6587\u8282\u70b9".repeat(4) + " offline"
        mountDetail(contact.copy(name = longName))
        val heading = compose.onNode(hasText(longName) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        assertFont200Unclipped(heading)
        val measured = heading.fetchSemanticsNode()
        val root = compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot
        assertTrue("The full multiline heading must fit its actual current viewport",
            measured.boundsInRoot.height == measured.size.height.toFloat() &&
                measured.boundsInRoot.top >= root.top && measured.boundsInRoot.bottom <= root.bottom)
        val edit = host.get().getString(C.contactsCommonEdit)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(edit))
        compose.onNodeWithText(edit).performScrollTo().performClick()
        val editor = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
            .performScrollTo().performClick()
        editor.performTextInput("\u672c\u5730\u6635\u79f0")
        assertFont200Unclipped(editor)
        compose.onNodeWithText(host.get().getString(C.contactsCommonSave)).performScrollTo().assertIsDisplayed()
        val cancel = compose.onNodeWithText(host.get().getString(C.contactsCommonCancel)).performScrollTo()
        assertFont200Unclipped(cancel)
        assertVisibleClickTargetsAtLeast48Dp()
        cancel.performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(0)
        compose.onNodeWithText(edit).assertIsDisplayed()
    }

    fun offlineClearMessagesRequiresConfirmationAndExplainsUnavailableServicesWithoutDismissingDetail() {
        var backedOut = false
        mountDetail(contact, onBack = { backedOut = true })
        val clear = host.get().getString(C.contactsDetailClearMessages)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(clear))
        val button = compose.onNodeWithText(clear).performScrollTo().assertIsDisplayed()
        assertFont200Unclipped(button)
        assertVisibleClickTargetsAtLeast48Dp()
        button.performClick()
        val confirmation = C.contactsDetailAlertClearMessagesMessage(host.get().resources, contact.displayName)
        assertFont200Unclipped(compose.onNodeWithText(confirmation).assertIsDisplayed())
        compose.onNodeWithText(host.get().getString(C.contactsCommonCancel)).performClick()
        compose.onNodeWithText(confirmation).assertDoesNotExist()
        button.performClick()
        compose.onNodeWithText(host.get().getString(C.contactsCommonOk)).performClick()
        val unavailable = host.get().getString(C.contactsDetailErrorServicesUnavailable)
        assertFont200Unclipped(compose.onNodeWithText(unavailable).assertIsDisplayed())
        compose.onNodeWithText(host.get().getString(C.contactsCommonError)).assertIsDisplayed()
        compose.onNodeWithText(host.get().getString(C.contactsCommonOk)).performClick()
        compose.onNodeWithText(unavailable).assertDoesNotExist()
        compose.onNodeWithText(clear).assertIsDisplayed()
        assertTrue("A failed offline clear must not simulate success/navigation", !backedOut)
    }

    private fun mountDetail(
        cachedContact: ContactDTO,
        onBack: () -> Unit = { error("Unexpected Back") },
        dependencies: NodesFeatureDependencies = offlineDependencies(cachedContact),
    ) = audit.mountDetail(cachedContact, onBack, dependencies)

    private fun offlineDependencies(
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
    ): NodesFeatureDependencies = audit.offlineDependencies(cachedContact, cachedContacts, contactUriCodec, clock)
}
