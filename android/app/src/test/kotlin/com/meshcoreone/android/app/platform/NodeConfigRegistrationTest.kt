// AndroidOnly: WP-318 Mounted production Settings routes over the real AppContainer/service and a protocol fake.
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.navigation.*
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.remote.NodeConfigJson
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigDocuments
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [31, 37], qualifiers = "w360dp-h900dp")
class NodeConfigRegistrationTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class Documents : NodeConfigDocuments {
        var picked: ByteArray? = """{"name":"Imported"}""".toByteArray()
        var saved: ByteArray? = null
        override suspend fun pick() = picked
        override suspend fun save(name: String, bytes: ByteArray): Boolean {
            assertTrue(name.endsWith(".json"))
            saved = bytes
            return true
        }
    }

    @Test fun mountedAdvancedEntryIsDisabledWithoutAReadySession() = runTest {
        val h = ContainerHarness(this, store)
        val context = compose.activity
        val storage = MeshCoreStorage.get(context)
        try {
            val feature = AppSettingsFeature.create(h.container, storage)
            compose.runOnUiThread {
                h.container.navigation.selectTab(AppTab.SETTINGS)
                h.container.navigation.navigateToSetting(SettingsDetail.ADVANCED)
                context.setContent {
                    val navigation by h.container.navigation.state.collectAsState()
                    MeshCoreTheme {
                        AppSettingsContent(feature, navigation.activeStack.last().destination, h.container.navigation, Documents())
                    }
                }
            }
            compose.onNodeWithText(context.getString(S.configExportExport)).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(context.getString(S.configImportImportConfig)).performScrollTo().assertIsNotEnabled()
            assertTrue(h.links.radios.isEmpty())
        } finally {
            compose.runOnUiThread { context.setContent {} }
            h.container.close()
            storage.close()
        }
    }

    @Test fun advancedRoutesExportAndImportThroughTheRealService() = runTest {
        val h = ContainerHarness(this, store)
        val context = compose.activity
        val storage = MeshCoreStorage.get(context)
        val docs = Documents()
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val feature = AppSettingsFeature.create(h.container, storage)
            val holder = feature.nodeConfig(context.resources)
            val radio = h.links.radios.single()
            radio.respond = { command -> if ((command[0].toInt() and 255) == 0x08) listOf(Bytes.of(0)) else null }
            compose.runOnUiThread {
                h.container.navigation.selectTab(AppTab.SETTINGS)
                context.setContent {
                    val navigation by h.container.navigation.state.collectAsState()
                    MeshCoreTheme {
                        AppSettingsContent(feature, navigation.activeStack.last().destination, h.container.navigation, docs)
                    }
                }
            }
            compose.onNodeWithText(context.getString(S.advancedSettingsTitle)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(S.configExportExport)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(S.configExportExportSelected)).assertIsNotEnabled()
            compose.onNodeWithText(context.getString(S.configExportOtherSettings)).performClick()
            compose.onNodeWithText(context.getString(S.configExportExportSelected)).performScrollTo().performClick()
            h.eventually("Selected JSON exported") { docs.saved != null }
            val exported = NodeConfigJson.decode(requireNotNull(docs.saved))
            assertNotNull(exported.otherSettings)
            assertNull(exported.name)
            compose.onNodeWithText(context.getString(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonDone)).performClick()
            compose.onNodeWithText(context.getString(S.configImportImportConfig)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(S.configImportSelectFile)).performClick()
            h.eventually("Parsed preview loaded") { holder.importState.value.config != null }
            compose.onNodeWithText(S.configImportNew(context.resources, "Imported")).assertIsDisplayed()
            compose.onNodeWithText(context.getString(S.configImportApplyButtonOverwrite)).performScrollTo().performClick()
            h.eventually("Preview confirmed") { holder.importState.value.confirmation }
            compose.onNodeWithText(context.getString(S.configImportConfirmTitleOverwrite)).assertIsDisplayed()
            compose.onAllNodesWithText(context.getString(S.configImportApplyButtonOverwrite)).onLast().performClick()
            h.eventually("Import acknowledged and refreshed") { holder.importState.value.complete }
            compose.onNodeWithText(context.getString(S.configImportImportSuccess)).performScrollTo().assertIsDisplayed()
            assertEquals(1, radio.sent.count { (it[0].toInt() and 255) == 0x08 })
            assertTrue(h.container.sessions.current != null)
        } finally {
            compose.runOnUiThread { context.setContent {} }
            h.container.close()
            storage.close()
        }
    }

    @Test fun confirmedRequestCannotApplyAfterDisconnectAndCancelledPickerStaysIdle() = runTest {
        val h = ContainerHarness(this, store)
        val context = compose.activity
        val storage = MeshCoreStorage.get(context)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val feature = AppSettingsFeature.create(h.container, storage)
            val holder = feature.nodeConfig(context.resources)
            val documents = Documents().also { it.picked = null }
            holder.pick(documents); h.eventually("Cancelled picker returned idle") { !holder.importState.value.parsing }
            assertNull(holder.importState.value.config)
            assertNull(holder.importState.value.error)
            documents.picked = """{"name":"TestNode"}""".toByteArray()
            holder.pick(documents); h.eventually("Preview loaded") { holder.importState.value.config != null }
            holder.prepareConfirmation(); h.eventually("Preview validated") { holder.importState.value.confirmation }
            assertTrue(holder.importState.value.confirmation)
            h.manager.disconnect(); h.settle()
            holder.apply(); h.settle()
            assertNotNull(holder.importState.value.error)
            assertFalse(holder.importState.value.complete)
        } finally {
            h.container.close()
            storage.close()
        }
    }
}
