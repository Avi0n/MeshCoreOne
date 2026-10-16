package one.meshcore.shared.map.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.LPPSensorType
import one.meshcore.shared.remotenode.LPPValue
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_accessibility_view_location_on_map
import one.meshcore.shared.room.InMemoryRoomStore
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomAdminSession
import one.meshcore.shared.room.ui.RoomStatusOverlay
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.theme.LocalUseDarkTheme
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration

@RunWith(AndroidJUnit4::class)
class RoomMapAppearanceInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun roomStatusOverlayMapFollowsLiveGlobalSettings() {
        val session = session()
        val store = InMemoryRoomStore()
        runBlocking { store.seedSession(session) }
        val admin = RoomAdminService(GpsSession(), store)
        val renderer = MapAppearanceRecordingRenderer()
        val preference = mutableStateOf(AppColorSchemePreference.DARK)
        val offline = mutableStateOf(true)
        lateinit var openMap: String
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    openMap =
                        stringResource(Res.string.remotenodes_remote_nodes_status_accessibility_view_location_on_map)
                    CompositionLocalProvider(
                        LocalMapLibreHostRenderer provides renderer,
                        LocalUseDarkTheme provides false,
                        LocalMapColorSchemePreference provides preference.value,
                        LocalMapOffline provides offline.value,
                    ) {
                        RoomStatusOverlay(
                            session = session,
                            roomAdminService = { admin },
                            nodeSnapshotService = { null },
                            contactService = { null },
                            radioId = { session.radioID },
                            isRadioReady = true,
                            locationService = null,
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(openMap).performScrollTo().performClick()
        compose.onNodeWithTag(MapAppearanceRecordingRenderer.MAP_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(AppColorSchemePreference.DARK, renderer.preference)
            assertEquals(true, renderer.offline)
            preference.value = AppColorSchemePreference.LIGHT
            offline.value = false
        }
        compose.runOnIdle {
            assertEquals(AppColorSchemePreference.LIGHT, renderer.preference)
            assertEquals(false, renderer.offline)
        }
    }

    private class GpsSession : RoomAdminSession {
        override suspend fun requestTelemetry(publicKey: ByteArray, timeout: Duration) = TelemetryResponse(
            publicKeyPrefix = publicKey.copyOf(6),
            rawData = byteArrayOf(),
            points = listOf(LPPDataPoint(1, LPPSensorType.GPS, LPPValue.Gps(37.77, -122.42, 5.0))),
        )

        override suspend fun requestStatus(publicKey: ByteArray, timeout: Duration) = StatusResponse(
            layout = "roomServer", publicKeyPrefix = publicKey.copyOf(6), battery = 4_000, txQueueLength = 0,
            noiseFloor = -100, lastRSSI = -80, packetsReceived = 0, packetsSent = 0, airtime = 0, uptime = 0,
            sentFlood = 0, sentDirect = 0, receivedFlood = 0, receivedDirect = 0, fullEvents = 0, lastSNR = 0.0,
            directDuplicates = 0, floodDuplicates = 0, rxAirtime = 0, receiveErrors = 0,
        )

        override suspend fun sendCommand(publicKey: ByteArray, command: String, timeout: Duration): String =
            error("No CLI command is expected")

        override suspend fun sendRawCommand(publicKey: ByteArray, command: String, timeout: Duration): String =
            error("No CLI command is expected")
    }

    private companion object {
        fun session() = RemoteNodeSessionDto(
            id = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB"),
            radioID = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
            publicKey = ByteArray(32), name = "Test Room", role = RemoteNodeRole.ROOM_SERVER,
            latitude = 0.0, longitude = 0.0, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN,
            unreadCount = 0, notificationLevel = NotificationLevel.ALL, neighborCount = 0, lastSyncTimestamp = 0,
        )
    }
}
