// PortedFrom: MC1Tests/Utilities/MeshCoreURLParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-405 Actual App dialog consent, radio write and Room preference regression.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.meshcoreone.android.app.deeplinks.AppDeepLinkRouter
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.navigation.AppPendingChannel
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.*

@Config(sdk = [31, 37], qualifiers = "en-rUS-w390dp-h1000dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelConfirmationConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun externalPrivateHashtagWarningPrecedesConsentAndPersistsExactSecretAndScope() = runTest {
        confirmPrivateHashtag(fromChat = false, name = "#avion-testing2", region = "testregion")
    }

    @Test fun chatPrivateHashtagWarningPrecedesConsentAndPersistsExactSecretAndScope() = runTest {
        confirmPrivateHashtag(fromChat = true, name = "#avion-testing2", region = "testregion")
    }

    @Test fun externalPrivateTestHashtagWithoutRegionRemainsJoinableAfterWarning() = runTest {
        confirmPrivateHashtag(fromChat = false, name = "#test", region = null)
    }

    @Test fun chatPrivateTestHashtagWithoutRegionRemainsJoinableAfterWarning() = runTest {
        confirmPrivateHashtag(fromChat = true, name = "#test", region = null)
    }

    @Test fun disconnectedConfirmationShowsMetadataButCannotWriteAndCancellationClearsIt() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val navigation = h.container.navigation
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
            external.routeExternal(
                "meshcore://channel/add?name=%23test&secret=00112233445566778899AABBCCDDEEFF&region_scope=testregion",
            )
            compose.runOnUiThread {
                host.get().setContent { MeshCoreTheme(motionScale = 0f) {
                    AppPendingChannel(h.container.chatsFeature, navigation)
                } }
            }
            compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinfrommessage_hashtagsecretmismatch))
                .assertIsDisplayed()
            compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinfrommessage_regionscope, "testregion"))
                .assertIsDisplayed()
            compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
            assertTrue(h.links.radios.isEmpty())
            compose.onNodeWithText(host.get().getString(L.commonCancel)).performClick()
            assertNull(navigation.state.value.pendingChannelLink)
            assertTrue(h.links.radios.isEmpty())
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    private suspend fun TestScope.confirmPrivateHashtag(fromChat: Boolean, name: String, region: String?) {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> if (frame[0].toInt() == 0x20) listOf(Bytes.of(0)) else null }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            h.eventually("initial channel sync finishes before a confirmed write") {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            val feature = h.container.chatsFeature
            val navigation = h.container.navigation
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
            val chat = ChatLinkRouter(feature.list.links, backgroundScope)
            val radioId = assertNotNull(h.appState.currentRadioId)
            val firmware = h.links.radios.single()
            val secret = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
            compose.runOnUiThread {
                host.get().setContent { MeshCoreTheme(motionScale = 0f) { AppPendingChannel(feature, navigation) } }
            }
            run {
                val before = firmware.sent.count { it[0].toInt() == 0x20 }
                val uri = "meshcore://channel/add?name=%23${name.drop(1)}&secret=${secret.hexString}" +
                    (region?.let { "&region_scope=$it" } ?: "")
                if (fromChat) assertTrue(chat.route(uri)) else external.routeExternal(uri)
                testScheduler.runCurrent()
                compose.waitUntil(10_000) {
                    h.settle()
                    compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinfrommessage_hashtagsecretmismatch))
                    .assertIsDisplayed()
                region?.let {
                    compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinfrommessage_regionscope, it))
                        .assertIsDisplayed()
                }
                assertEquals(before, firmware.sent.count { it[0].toInt() == 0x20 }, "No write before consent")
                assertTrue(store.fetchChannels(radioId).none { it.name == name })
                compose.waitUntil(10_000) {
                    h.settle()
                    !compose.onNodeWithText(host.get().getString(L.commonOk))
                        .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
                }
                compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
                compose.waitUntil(10_000) {
                    h.settle()
                    navigation.state.value.pendingChannelLink == null
                }
                val saved = store.fetchChannels(radioId).single { it.name == name }
                assertEquals(secret, saved.secret)
                assertEquals(before + 1, firmware.sent.count { it[0].toInt() == 0x20 })
                assertEquals(PacketBuilder.setChannel(saved.index, name, secret), firmware.sent.last { it[0].toInt() == 0x20 })
                assertEquals(region?.let(ChannelFloodScope::Region) ?: ChannelFloodScope.Inherit, saved.floodScope)
                assertEquals(saved.id, navigation.state.value.pendingChannel?.id)
            }
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }
}
