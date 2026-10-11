// AndroidOnly: WP-407 Positive Compose discovery of native Chats/Settings with the actual process adapters.
package com.meshcoreone.android.app.platform

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.navigation.*
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [31], qualifiers = "w360dp-h800dp")
class NativeRegistrationTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun chatsAndSettingsDiscoverRealOfflineContentAndNativeSubpages() = runTest {
        val h = ContainerHarness(this, store)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        try {
            val settings = AppSettingsFeature.create(h.container, storage)
            compose.runOnUiThread {
                compose.activity.setContent {
                    MeshCoreTheme {
                        NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature, settings = settings)
                    }

                    @Test fun aNotificationOpensAnOlderMessageThroughTheRealPagedTimeline() = runTest {
                        val h = ContainerHarness(this, store)
                        try {
                            h.manager.connect(h.target()); h.settle(); h.assertReady()
                            val radio = requireNotNull(h.appState.currentRadioId)
                            val contact = com.meshcoreone.android.core.model.ContactDTO(
                                radioId = radio, publicKey = com.meshcoreone.android.core.protocol.bytes.Bytes(ByteArray(32) { 4 }),
                                name = "Synthetic notification peer", lastHeardTimestamp = null,
                            )
                            store.saveContact(contact)
                            val messages = (0..119).map { index ->
                                val time = java.time.Instant.ofEpochSecond(1_700_000_000L + index)
                                com.meshcoreone.android.core.model.MessageDTO(
                                    radioId = radio, contactID = contact.id, text = "Synthetic notification anchor $index",
                                    timestamp = (1_700_000_000L + index).toUInt(), createdAt = time, sortDate = time,
                                ).also { store.saveMessage(it) }
                            }
                            compose.runOnUiThread {
                                h.container.navigation.navigateToChat(contact, messages.first().id)
                                compose.activity.setContent {
                                    MeshCoreTheme { NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature) }
                                }
                            }
                            compose.waitUntil(30_000) { h.container.navigation.state.value.pendingScrollToMessageID == null }
                            compose.onNodeWithText(messages.first().text).assertIsDisplayed()
                        } finally {
                            compose.runOnUiThread { compose.activity.setContent {} }
                            h.container.close()
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(C.chatsEmptyStateNoConversationsTitle)).assertIsDisplayed()
            compose.onNodeWithTag("tab:SETTINGS").performClick()
            compose.onNodeWithText(context.getString(S.appSettingsHeader)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(S.languageTitle)).performScrollTo().performClick()
            compose.onNodeWithText("Deutsch").assertIsDisplayed()
            compose.runOnUiThread { h.container.navigation.back() }
            compose.onNodeWithText(context.getString(S.mapsTitle)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(S.mapsDisplayHeader)).assertIsDisplayed()
            assertEquals(0, h.lifecycle.created.size)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.container.close()
            storage.close()
        }
    }
}
