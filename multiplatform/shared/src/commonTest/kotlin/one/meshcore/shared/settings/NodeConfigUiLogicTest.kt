package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeConfigUiLogicTest {

    @Test
    fun autoSelectSections_picksOnlyPresentNonZeroSections() {
        val config = MeshCoreNodeConfig(
            name = "Alpha",
            radioSettings = MeshCoreNodeConfig.RadioSettings(
                frequency = 910_525u,
                bandwidth = 62_500u,
                spreadingFactor = 7u,
                codingRate = 5u,
                txPower = 22,
            ),
            positionSettings = MeshCoreNodeConfig.PositionSettings("0", "0"),
            channels = listOf(MeshCoreNodeConfig.ChannelConfig(name = "public", secret = "ab".repeat(16))),
        )
        val sections = autoSelectSections(config)
        assertTrue(sections.nodeIdentity)
        assertTrue(sections.radioSettings)
        assertFalse(sections.positionSettings) // zero pair skipped
        assertFalse(sections.otherSettings)
        assertTrue(sections.channels)
        assertFalse(sections.contacts)
    }

    @Test
    fun confirmKind_addOnly_whenChannelsWithoutOverwrite() {
        val kind = configImportConfirmKind(
            ConfigSections(channels = true, contacts = true),
            channelsWouldOverwrite = false,
        )
        assertEquals(ConfigImportConfirmKind.ADD, kind)
    }

    @Test
    fun confirmKind_overwrite_whenRadioOnly() {
        val kind = configImportConfirmKind(
            ConfigSections(radioSettings = true),
            channelsWouldOverwrite = false,
        )
        assertEquals(ConfigImportConfirmKind.OVERWRITE, kind)
    }

    @Test
    fun confirmKind_mixed_whenRadioAndContacts() {
        val kind = configImportConfirmKind(
            ConfigSections(radioSettings = true, contacts = true),
            channelsWouldOverwrite = false,
        )
        assertEquals(ConfigImportConfirmKind.MIXED, kind)
    }

    @Test
    fun confirmKind_channelsOverwriteCountsAsOverwriteBranch() {
        val kind = configImportConfirmKind(
            ConfigSections(channels = true),
            channelsWouldOverwrite = true,
        )
        // channels alone with overwrite is both overwrite and additive → MIXED
        assertEquals(ConfigImportConfirmKind.MIXED, kind)
    }

    @Test
    fun sanitizeNodeName_stripsUnsafeAndTrimsUnderscores() {
        assertEquals("My_Node", sanitizeNodeNameForFilename("My Node!"))
        assertEquals("unknown", sanitizeNodeNameForFilename("!!!"))
        assertEquals("a-b_c", sanitizeNodeNameForFilename("a-b_c"))
    }

    @Test
    fun exportBasename_usesFallbackAndTimestamp() {
        assertEquals(
            "Alpha_meshcore_config_2026-07-12-153045",
            nodeConfigExportBasename("Alpha", null, "2026-07-12-153045"),
        )
        assertEquals(
            "from_file_meshcore_config_t",
            nodeConfigExportBasename(null, "from file", "t"),
        )
        assertEquals(
            "unknown_meshcore_config_t",
            nodeConfigExportBasename(null, null, "t"),
        )
    }

    @Test
    fun formatRadioSettings_matchesCompanionShape() {
        val radio = MeshCoreNodeConfig.RadioSettings(
            frequency = 910_525u,
            bandwidth = 62_500u,
            spreadingFactor = 7u,
            codingRate = 5u,
            txPower = 22,
        )
        assertEquals(
            "910.525 MHz, BW 62.5 kHz, SF 7, CR 5",
            formatRadioSettingsForDisplay(radio),
        )
    }
}
