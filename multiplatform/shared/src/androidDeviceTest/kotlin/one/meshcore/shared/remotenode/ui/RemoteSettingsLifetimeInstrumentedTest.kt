package one.meshcore.shared.remotenode.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_contact_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_tab_cli
import one.meshcore.shared.room.ui.RoomManagementSurface
import one.meshcore.shared.room.ui.applyContactInfo
import one.meshcore.shared.room.ui.setContactInfoExpanded
import one.meshcore.shared.room.ui.setOwnerInfo
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RemoteSettingsLifetimeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shown = mutableStateOf(true)
    private var fixture: RemoteSettingsLifetimeFixture? = null

    @After
    fun tearDown() {
        fixture?.reading?.cancel()
        fixture?.commandReply?.cancel()
        fixture?.cliReply?.cancel()
        runBlocking { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
        fixture?.close()
    }

    @Test
    fun hidingRepeaterManagementKeepsItsPendingReading() {
        val remote = RemoteSettingsLifetimeFixture(compose.activity, scope).also { fixture = it }
        val workspaces = remote.workspaces(scope)
        lateinit var editor: String
        lateinit var contactInfo: String
        val model = workspaces.family(remote.session).repeaterSettings()
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    editor = stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info)
                    contactInfo = stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info)
                    if (shown.value) {
                        NodeManagementSurface(
                            workspaces = workspaces,
                            session = remote.session,
                            routePathContact = remote.contact,
                            repeaterAdminService = { remote.admin },
                            nodeSnapshotService = { null },
                            surface = RepeaterSurfaceInputs(radioId = { remote.session.radioID }),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, remote.readingCalls) }
        compose.onNodeWithText(contactInfo).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { remote.readingCalls > 0 }
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("Hiding remote settings cancelled its pending reading", remote.readingCancelled)
            remote.reading.complete(OwnerInfoResponse("1.15.0", "Retained remote node", "Read while hidden"))
        }
        compose.waitUntil(TIMEOUT_MS) {
            model.state.value.ownerInfo == "Read while hidden"
        }
        compose.runOnIdle {
            model.setOwnerInfo("Retained repeater draft")
            model.setContactInfoExpanded(true)
            shown.value = true
        }
        compose.waitForIdle()
        compose.onNode(hasText(editor) and hasSetTextAction()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retained repeater draft").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(model, workspaces.family(remote.session).repeaterSettings())
            assertEquals(1, remote.readingCalls)
        }
    }

    @Test
    fun roomApplyCompletesHiddenAndReopensWithItsLaterDraft() {
        val remote = RemoteSettingsLifetimeFixture(compose.activity, scope).also { fixture = it }
        val workspaces = remote.workspaces(scope)
        val model = workspaces.family(remote.roomSession).roomSettings()
        lateinit var editor: String
        compose.runOnIdle { model.setContactInfoExpanded(true) }
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    editor = stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info)
                    if (shown.value) {
                        RoomManagementSurface(
                            session = remote.roomSession,
                            roomAdminService = { remote.roomAdmin },
                            workspaces = workspaces,
                            radioId = { remote.session.radioID },
                        )
                    }
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { model.state.value.contactInfoLoaded }
        compose.onNode(hasText(editor) and hasSetTextAction()).performScrollTo()
            .performTextReplacement("Submitted owner")
        compose.runOnIdle { model.applyContactInfo() }
        compose.waitUntil(TIMEOUT_MS) { remote.commandCalls == 1 }
        compose.runOnIdle {
            shown.value = false
            model.setOwnerInfo("Later room draft")
        }
        compose.waitForIdle()
        compose.runOnIdle { remote.commandReply.complete("OK") }
        compose.waitUntil(TIMEOUT_MS) { !model.state.value.isApplyingContactInfo }
        val completed = SystemClock.elapsedRealtime()
        compose.waitUntil(TIMEOUT_MS) { SystemClock.elapsedRealtime() - completed >= APPLY_SUCCESS_FLASH_MS * 2 }
        compose.runOnIdle {
            assertTrue(model.state.value.contactInfoApplySuccess)
            assertTrue(model.state.value.contactInfoModified)
            shown.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithText("Later room draft").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertSame(model, workspaces.family(remote.roomSession).roomSettings()) }
        capture("remote-room-retained.png")
    }

    @Test
    fun roomCliKeepsPendingCommandAndTranscriptAcrossHideAndReturn() {
        val remote = RemoteSettingsLifetimeFixture(compose.activity, scope).also { fixture = it }
        val workspaces = remote.workspaces(scope)
        val cli = workspaces.cli(remote.roomSession, remoteCliMessages())
        lateinit var cliLabel: String
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    cliLabel = stringResource(Res.string.remotenodes_remote_nodes_settings_tab_cli)
                    if (shown.value) {
                        RoomManagementSurface(
                            session = remote.roomSession,
                            roomAdminService = { remote.roomAdmin },
                            workspaces = workspaces,
                            radioId = { remote.session.radioID },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(cliLabel).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            cli.setInput("retained cli")
            cli.submit()
        }
        compose.waitUntil(TIMEOUT_MS) { remote.cliCalls == 1 }
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { remote.cliReply.complete("Delayed CLI reply") }
        compose.waitForIdle()
        compose.runOnIdle { shown.value = true }
        compose.waitForIdle()
        compose.onNodeWithText(cliLabel).performClick()
        compose.onNodeWithText("Delayed CLI reply").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, remote.cliCalls) }
    }

    private fun remoteCliMessages() = one.meshcore.shared.remotenode.NodeCliMessages(
        bannerConnected = { "Connected to $it" },
        bannerHint = "Help available",
        cancelled = "Cancelled",
        rebootSent = "Reboot sent",
        promptSuffix = "> ",
        help = listOf("Help"),
    )

    private fun capture(name: String) {
        val file = File(compose.activity.cacheDir, name)
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
