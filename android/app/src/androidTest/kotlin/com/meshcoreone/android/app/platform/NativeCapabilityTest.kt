// AndroidOnly: WP-407 Real API31/37 permission facts, denied camera recovery and offline native composer/catalog.
package com.meshcoreone.android.app.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.app.navigation.AppQrScanner
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.core.ble.BluetoothAvailability
import com.meshcoreone.android.core.connectivity.permissions.*
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as N
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeCapabilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualDeniedOrGrantedPermissionsStayCapabilityScopedOnTheSelectedApi() {
        val context = compose.activity
        assertTrue(Build.VERSION.SDK_INT == 31 || Build.VERSION.SDK_INT == 37)
        val phase = InstrumentationRegistry.getArguments().getString("permissionState", "denied")
        assertTrue("Unknown execution phase: $phase", phase == "denied" || phase == "granted")
        val granted = phase == "granted"
        val snapshot = AndroidPermissionState(context).snapshot()
        for (permission in ConnectivityPermission.entries) {
            if (Build.VERSION.SDK_INT >= permission.minimumSdk) {
                assertEquals("Real OS grant for ${permission.manifestName}", granted, permission in snapshot.granted)
            }
        }
        if (!granted) {
            assertEquals(BluetoothAvailability.Unauthorized, bluetoothAvailability(context))
            assertTrue(ConnectivityPermissionPolicy.evaluate(ConnectivityFeature.BleConnection, snapshot, PairingMode.ScanFallback) is FeatureReadiness.Denied)
        }
        val lan = ConnectivityPermissionPolicy.evaluate(ConnectivityFeature.LanConnection, snapshot, PairingMode.ScanFallback)
        if (Build.VERSION.SDK_INT == 37 && !granted) assertTrue(lan is FeatureReadiness.Denied)
        else assertTrue(lan is FeatureReadiness.Ready)
        val notification = ConnectivityPermissionPolicy.evaluate(ConnectivityFeature.ConnectionNotification, snapshot, PairingMode.ScanFallback)
        assertTrue(notification is FeatureReadiness.Ready)
        assertEquals(Build.VERSION.SDK_INT >= 33 && !granted,
            Advisory.NotificationsHidden in (notification as FeatureReadiness.Ready).advisories)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.CAMERA))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Test fun realOfflineConversationAndFullEmojiInsertionDoNotRequestOptionalPermissions() = runBlocking {
        val app = compose.activity.application as MeshCoreApplication
        val container = withTimeout(30_000) { app.container.await() }
        val store = container.processStore
        val radio = RadioId(UUID.randomUUID())
        val contact = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 4 }),
            name = "Synthetic offline peer", lastHeardTimestamp = null)
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "Synthetic retained offline message",
            timestamp = 1u, direction = MessageDirection.INCOMING)
        store.saveContact(contact)
        store.saveMessage(message)
        try {
            compose.runOnUiThread {
                container.navigation.navigateToChat(contact)
                compose.activity.setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        NativeNavigationShell(container.navigation, chats = container.chatsFeature, settings = container.settingsFeature)
                    }
                }
            }
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(message.text).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(message.text).assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(C.reactionsMoreEmojis)).performClick()
            val search = compose.activity.getString(C.reactionsEmojiSearchPlaceholder)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(search).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(search).performTextInput("grinning")
            compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("grinning face").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("grinning face").performClick()
            compose.waitUntil(10_000) {
                container.appState.draftStore.draft(ChatConversationID(radio, ConversationKey.DM(contact.id))) == "😀"
            }
            assertEquals(message.text, store.fetchMessages(EntityKey(radio, contact.id), 10, 0).single().text)
            assertEquals(PackageManager.PERMISSION_DENIED, compose.activity.checkSelfPermission(Manifest.permission.CAMERA))
            assertEquals(PackageManager.PERMISSION_DENIED, compose.activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
            if (Build.VERSION.SDK_INT == 31) {
                try { compose.activity.packageManager.getPackageInfo("com.google.android.gms", 0); fail("Run the API31 no-GMS dimension on AOSP") }
                catch (_: PackageManager.NameNotFoundException) {}
            }
        } finally {
            store.deleteMessagesForContact(EntityKey(radio, contact.id))
            container.appState.draftStore.clearDraft(ChatConversationID(radio, ConversationKey.DM(contact.id)))
        }
    }

    @Test fun actualCameraDenialLeavesManualInputAndOneDecodeUsable() {
        assertEquals(PackageManager.PERMISSION_DENIED, compose.activity.checkSelfPermission(Manifest.permission.CAMERA))
        val decoded = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.setContent { MeshCoreTheme(motionScale = 0f) { AppQrScanner({}, { decoded += it }) } }
        }
        compose.onNodeWithText(compose.activity.getString(N.contactsScanUnavailableDescription)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(N.contactsAddPasteURL)).performTextInput("synthetic-manual-link")
        val submit = compose.onNodeWithText(compose.activity.getString(N.contactsAddView))
        submit.performClick()
        submit.performClick()
        assertEquals(listOf("synthetic-manual-link"), decoded)
    }
}
