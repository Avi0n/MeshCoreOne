package one.meshcore.shared.room.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_error_pin_save_failed
import one.meshcore.shared.resources.chats_row_pin
import one.meshcore.shared.resources.settings_public_key_copy
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomInfoClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val clipboard = HoldingClipboard()
    private val actions = mutableListOf<String>()
    private lateinit var copyDescription: String
    private lateinit var pinLabel: String
    private lateinit var pinFailureLabel: String
    private var failPinWrite = false

    @After
    fun releaseWrite() {
        clipboard.release.complete(Unit)
    }

    @Test
    fun copyWritesCompleteUppercaseKeyWithoutOtherActions() {
        showRoom()
        copyKey()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            val data = requireNotNull(clipboard.entries.single()).clipData
            assertEquals(1, data.itemCount)
            assertEquals(COPIED_KEY, data.getItemAt(0).text.toString())
            assertEquals(0, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertTrue(actions.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.completed)
            assertEquals(0, clipboard.cancelled)
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun removingRoomCancelsPendingCopyWithoutOtherActions() {
        showRoom()
        copyKey()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(0, clipboard.completed)
            assertTrue(actions.isEmpty())
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertTrue(actions.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, clipboard.completed)
            assertTrue(actions.isEmpty())
        }
    }

    private fun copyKey() {
        compose.onNodeWithContentDescription(copyDescription).performScrollTo().performClick()
    }

    @Test
    fun roomPinCanBeSavedWhileTheRadioIsDisconnected() {
        showRoom()
        compose.onNodeWithText(pinLabel).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(pinLabel).assertIsOn()
        compose.runOnIdle { assertEquals(listOf("pin"), actions) }
    }

    @Test
    fun roomPinSaveFailureRollsBackAndShowsLocalizedError() {
        failPinWrite = true
        showRoom()
        compose.onNodeWithText(pinLabel).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(pinLabel).assertIsOff()
        compose.onNodeWithText(pinFailureLabel).assertIsDisplayed()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    private fun showRoom() {
        compose.setContent {
            pinLabel = stringResource(Res.string.chats_row_pin)
            pinFailureLabel = stringResource(Res.string.chats_error_pin_save_failed)
            copyDescription = stringResource(Res.string.settings_public_key_copy)
            CompositionLocalProvider(
                LocalClipboard provides clipboard,
                LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        actions += "haptic"
                    }
                },
            ) {
                MaterialTheme {
                    if (visible.value) {
                        RoomInfoSheet(
                            session = ROOM,
                            onPinnedChange = {
                                if (failPinWrite) error("persistence unavailable")
                                actions += "pin"
                            },
                            onNotificationLevelChange = { actions += "notification" },
                            isRadioReady = { false },
                            onOpenStatus = { actions += "status" },
                            onOpenSettings = { actions += "settings" },
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val KEY_SIZE = 32
        const val COPIED_KEY = "A30102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1EFF"
        val PUBLIC_KEY = ByteArray(KEY_SIZE) { it.toByte() }.apply {
            this[0] = 0xA3.toByte()
            this[lastIndex] = 0xFF.toByte()
        }
        val ROOM = RemoteNodeSessionDto(
            id = Uuid.normalized("10000000-0000-0000-0000-000000000001"),
            radioID = Uuid.normalized("10000000-0000-0000-0000-000000000002"),
            publicKey = PUBLIC_KEY,
            name = "Room",
            role = RemoteNodeRole.ROOM_SERVER,
            latitude = 0.0,
            longitude = 0.0,
            isConnected = true,
            permissionLevel = RoomPermissionLevel.ADMIN,
            unreadCount = 0,
            notificationLevel = NotificationLevel.ALL,
            isPinned = false,
            neighborCount = 0,
            lastSyncTimestamp = 0,
        )
    }
}
