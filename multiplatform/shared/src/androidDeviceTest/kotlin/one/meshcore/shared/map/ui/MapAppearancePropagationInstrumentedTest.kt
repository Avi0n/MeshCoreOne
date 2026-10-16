package one.meshcore.shared.map.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.chat.pathdetail.MessagePathDisplay
import one.meshcore.shared.chat.pathdetail.PathHopRow
import one.meshcore.shared.chat.pathdetail.PathHopType
import one.meshcore.shared.chat.ui.MessagePathPresentation
import one.meshcore.shared.chat.ui.MessagePathSheet
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.contact.ui.ContactDetailLocationSection
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.remotenode.ui.NodeHistoryKind
import one.meshcore.shared.remotenode.ui.TelemetryOverviewScreen
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_location_picker_latitude
import one.meshcore.shared.resources.settings_location_picker_longitude
import one.meshcore.shared.resources.settings_location_picker_title
import one.meshcore.shared.resources.settings_node_set_location
import one.meshcore.shared.settings.ui.OfflineRegionPickerSheet
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.theme.LocalUseDarkTheme
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MapAppearancePropagationInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = MapAppearanceRecordingRenderer()

    @After
    fun cleanup() {
        scope.cancel()
    }

    @Test
    fun pathMapFollowsSavedAppearanceWhileChromeStaysLight() {
        val preferences = MapDisplayPreferencesStore(InMemoryPreferencesStore(), scope)
        preferences.setMapColorScheme(AppColorSchemePreference.DARK)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMapLibreHostRenderer provides renderer,
                    LocalUseDarkTheme provides false,
                    LocalMapColorSchemePreference provides AppColorSchemePreference.LIGHT,
                ) {
                    MessagePathSheet(
                        presentation = path(),
                        onDismiss = {},
                        mapDisplayPreferences = preferences,
                    )
                }
            }
        }
        compose.onNodeWithTag(MAP).assertIsDisplayed()
        compose.runOnIdle { assertEquals(AppColorSchemePreference.DARK, renderer.preference) }
        compose.runOnIdle { preferences.setMapColorScheme(AppColorSchemePreference.LIGHT) }
        compose.runOnIdle { assertEquals(AppColorSchemePreference.LIGHT, renderer.preference) }
    }

    @Test
    fun pathWithoutStoreInheritsGlobalAppearanceAndOfflineState() {
        compose.setContent {
            TestRoot(AppColorSchemePreference.DARK, offline = true) {
                MessagePathSheet(presentation = path(), onDismiss = {})
            }
        }
        assertMap(AppColorSchemePreference.DARK, offline = true)
    }

    @Test
    fun locationPickerFollowsLiveGlobalAppearanceAndOfflineState() {
        val preference = mutableStateOf(AppColorSchemePreference.DARK)
        val offline = mutableStateOf(false)
        lateinit var title: String
        lateinit var latitude: String
        lateinit var longitude: String
        lateinit var save: String
        compose.setContent {
            title = stringResource(Res.string.settings_location_picker_title)
            latitude = stringResource(Res.string.settings_location_picker_latitude)
            longitude = stringResource(Res.string.settings_location_picker_longitude)
            save = stringResource(Res.string.settings_node_set_location)
            TestRoot(preference.value, offline.value) {
                LocationPickerScreen(initial = LatLng(37.77, -122.42), locationService = null, onSave = {
                }, onCancel = {})
            }
        }
        assertMap(AppColorSchemePreference.DARK, offline = false)
        compose.runOnIdle {
            preference.value = AppColorSchemePreference.LIGHT
            offline.value = true
        }
        assertMap(AppColorSchemePreference.LIGHT, offline = true)
        compose.onNode(hasText(title) and !hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText(latitude).assertIsDisplayed()
        compose.onNodeWithText(longitude).assertIsDisplayed()
        compose.onNode(hasText(save) and hasClickAction()).assertIsDisplayed()
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file =
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "map-picker-appearance.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test
    fun offlineRegionPickerInheritsGlobalAppearanceAndOfflineState() {
        compose.setContent {
            TestRoot(AppColorSchemePreference.DARK, offline = true) {
                OfflineRegionPickerSheet(
                    initialCamera = camera(),
                    onConfirm = { _, _ -> },
                    onDismiss = {},
                    nowEpochSeconds = { 0L },
                )
            }
        }
        assertMap(AppColorSchemePreference.DARK, offline = true)
    }

    @Test
    fun offlineRegionPickerExplicitInputsOverrideGlobalDefaults() {
        compose.setContent {
            TestRoot(AppColorSchemePreference.DARK, offline = false) {
                OfflineRegionPickerSheet(
                    initialCamera = camera(),
                    onConfirm = { _, _ -> },
                    onDismiss = {},
                    nowEpochSeconds = { 0L },
                    isNetworkAvailable = false,
                    colorSchemePreference = AppColorSchemePreference.LIGHT,
                )
            }
        }
        assertMap(AppColorSchemePreference.LIGHT, offline = true)
    }

    @Test
    fun contactMiniMapInheritsGlobalAppearanceAndKeepsExplicitOfflineState() {
        compose.setContent {
            TestRoot(AppColorSchemePreference.DARK, offline = false) {
                ContactDetailLocationSection(contact(), isOffline = true, onExpandMap = {}, onOpenInMaps = null)
            }
        }
        assertMap(AppColorSchemePreference.DARK, offline = true)
    }

    @Test
    fun telemetryHistoryMapInheritsGlobalAppearanceAndOfflineState() {
        val snapshot = NodeStatusSnapshotDto(
            id = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD"),
            timestamp = 1_000.0,
            nodePublicKey = ByteArray(32),
            latitude = 37.77,
            longitude = -122.42,
        )
        compose.setContent {
            TimestampFormatting {
                TestRoot(AppColorSchemePreference.DARK, offline = true) {
                    TelemetryOverviewScreen(
                        snapshots = listOf(snapshot),
                        nowEpochSeconds = 1_000.0,
                        kind = NodeHistoryKind.TELEMETRY,
                    )
                }
            }
        }
        assertMap(AppColorSchemePreference.DARK, offline = true)
    }

    @Composable
    private fun TestRoot(preference: AppColorSchemePreference, offline: Boolean, content: @Composable () -> Unit) {
        MaterialTheme {
            CompositionLocalProvider(
                LocalMapLibreHostRenderer provides renderer,
                LocalUseDarkTheme provides false,
                LocalMapColorSchemePreference provides preference,
                LocalMapOffline provides offline,
                content = content,
            )
        }
    }

    private fun assertMap(preference: AppColorSchemePreference, offline: Boolean) {
        compose.onNodeWithTag(MAP).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(preference, renderer.preference)
            assertEquals(offline, renderer.offline)
        }
    }

    private companion object {
        const val MAP = MapAppearanceRecordingRenderer.MAP_TAG

        fun camera() = MapCameraRegion(LatLng(37.77, -122.42), 0.02, 0.02)

        fun contact() = ContactDto(
            id = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC"),
            radioID = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
            publicKey = ByteArray(32),
            name = "Alice",
            typeRawValue = 1,
            flags = 1,
            outPathLength = -1,
            outPath = byteArrayOf(),
            lastAdvertTimestamp = 0,
            latitude = 37.77,
            longitude = -122.42,
            lastModified = 0,
            isBlocked = false,
            isMuted = false,
            isFavorite = true,
            unreadCount = 0,
        )

        fun path() = MessagePathPresentation(
            initialDisplay = MessagePathDisplay(
                available = true,
                rows = listOf(
                    PathHopRow(PathHopType.Sender, "Sender", "01", null, LatLng(37.77, -122.42)),
                    PathHopRow(PathHopType.Receiver, "Receiver", null, 5.0, LatLng(37.78, -122.43)),
                ),
                hasIntermediateHops = false,
                pathString = "",
                pathStringForClipboard = "",
            ),
            arrivals = emptyList(),
        )
    }
}
