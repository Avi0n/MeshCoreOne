// AndroidOnly: WP-308 Mounted App composer prompts for the three channel-style families and a DM control.
// ReferenceInput: MC1Tests/Views/Chats/ChatConversationTypeTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/ChatConversationInputBar.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerPromptComposeTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun publicSlotUsesPublicPromptWithoutChangingSecurity() =
        assertPrompt(index = 0u, name = "Public", expected = AppChatsStrings.chatsChannelTypePublic, encrypted = false)

    @Test fun hashPrefixedChannelUsesPublicPromptWithoutChangingSecurity() =
        assertPrompt(index = 5u, name = "#general", expected = AppChatsStrings.chatsChannelTypePublic, encrypted = false)

    @Test fun privateChannelUsesPrivatePromptWithoutChangingSecurity() =
        assertPrompt(index = 3u, name = "Secret", expected = AppChatsStrings.chatsChannelTypePrivate, encrypted = true)

    @Test fun directMessageRetainsItsOwnPromptAndSecurity() =
        assertPrompt(index = null, name = "Alice", expected = AppChatsStrings.chatsInputPlaceholderDirectMessage, encrypted = true)

    private fun assertPrompt(index: UInt?, name: String, expected: Int, encrypted: Boolean) = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            if (index == null) {
                val contact = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 5 }), name = name)
                store.saveContact(contact)
                h.container.navigation.navigateToChat(contact)
            } else {
                val channel = ChannelDTO(radioId = radio, index = index.toUByte(), name = name)
                assertEquals(encrypted, channel.isEncryptedChannel)
                store.saveChannel(channel)
                h.container.navigation.navigateToChannel(channel)
            }
            val destination = h.container.navigation.state.value.activeStack.last().destination
            compose.runOnUiThread {
                host.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        AppChatsContent(h.container.chatsFeature, destination, h.container.navigation)
                    }
                }
            }
            val resources = host.get().resources
            val prompt = resources.getString(expected)
            compose.waitUntil(30_000) {
                h.settle()
                compose.onAllNodesWithText(prompt, useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText(prompt, useUnmergedTree = true).assertIsDisplayed()
            val label = resources.getString(AppChatsStrings.chatsInputAccessibilityLabel)
            val security = resources.getString(if (encrypted) AppChatsStrings.chatsInputEncrypted else AppChatsStrings.chatsInputNotEncrypted)
            val editor = compose.onNodeWithContentDescription("$label, $security")
            editor.assertIsDisplayed()
            assertEquals("", editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            for (other in listOf(
                AppChatsStrings.chatsInputPlaceholderDirectMessage,
                AppChatsStrings.chatsChannelTypePublic,
                AppChatsStrings.chatsChannelTypePrivate,
            ).filter { it != expected }) {
                compose.onNodeWithText(resources.getString(other), useUnmergedTree = true).assertDoesNotExist()
            }
            assertFalse(editor.fetchSemanticsNode().config.contains(SemanticsProperties.Error))
            assertEquals(1, h.lifecycle.created.size)
            assertEquals(0, h.links.radios.single().sent.count { it[0].toInt() == 2 })
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            h.container.close()
        }
    }
}
