package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decode/encode and the frozen companion-app JSON quirks for [MeshCoreNodeConfig], mirroring the Swift
 * `MeshCoreNodeConfig Decoding` suite: full/partial decode, absent sections → null, round-trip, the three
 * distinct `out_path` states (null vs empty vs value), `custom_name` null vs value, the explicit-null vs
 * omitted-`path_hash_mode` encode asymmetry, and `ConfigSections`.
 */
class NodeConfigTest {
    private fun contact(customName: String? = "Nick", outPath: String? = "0a0b", pathHashMode: UByte? = 1u) =
        MeshCoreNodeConfig.ContactConfig(
            type = 1u,
            name = "Bob",
            customName = customName,
            publicKey = "ab".repeat(32),
            flags = 3u,
            latitude = "1.5",
            longitude = "-2.5",
            lastAdvert = 100u,
            lastModified = 200u,
            outPath = outPath,
            pathHashMode = pathHashMode,
        )

    // MARK: Decode

    @Test
    fun fullConfigDecodesAllFields() {
        val json = """
            {
              "name": "MyNode",
              "public_key": "aa",
              "radio_settings": {"frequency": 910525, "bandwidth": 62500, "spreading_factor": 7,
                "coding_rate": 5, "tx_power": -9},
              "position_settings": {"latitude": "12.0", "longitude": "-3.0"},
              "other_settings": {"manual_add_contacts": 1, "advert_location_policy": 2},
              "channels": [{"name": "Public", "secret": "cd"}],
              "contacts": [{"type": 2, "name": "R", "custom_name": null, "public_key": "ff",
                "flags": 0, "latitude": "0", "longitude": "0", "last_advert": 5, "last_modified": 6,
                "out_path": null}]
            }
        """.trimIndent()
        val config = NodeConfigJson.decode(json)

        assertEquals("MyNode", config.name)
        assertEquals("aa", config.publicKey)
        assertEquals(910525u, config.radioSettings?.frequency)
        assertEquals((-9).toByte(), config.radioSettings?.txPower)
        assertEquals(false, config.positionSettings?.isZero)
        assertEquals(1u.toUByte(), config.otherSettings?.manualAddContacts)
        assertEquals(2u.toUByte(), config.otherSettings?.advertLocationPolicy)
        assertNull(config.otherSettings?.telemetryModeBase)
        assertEquals("Public", config.channels?.single()?.name)
        val c = config.contacts!!.single()
        assertEquals(2u.toUByte(), c.type)
        assertNull(c.customName)
        assertNull(c.outPath)
    }

    @Test
    fun channelsOnlyConfigHasNullForAbsentSections() {
        val config = NodeConfigJson.decode("""{"channels": [{"name": "P", "secret": "cd"}]}""")
        assertNull(config.name)
        assertNull(config.radioSettings)
        assertNull(config.contacts)
        assertEquals(1, config.channels?.size)
    }

    @Test
    fun emptyObjectDecodesAllNull() {
        val config = NodeConfigJson.decode("{}")
        assertNull(config.name)
        assertNull(config.radioSettings)
        assertNull(config.channels)
        assertNull(config.contacts)
    }

    @Test
    fun contactNullOutPathDecodesAsNull() {
        val config = NodeConfigJson.decode(contactJson(outPath = "null"))
        assertNull(config.contacts!!.single().outPath)
    }

    @Test
    fun contactEmptyStringOutPathDecodesAsEmptyString() {
        val config = NodeConfigJson.decode(contactJson(outPath = "\"\""))
        assertEquals("", config.contacts!!.single().outPath)
    }

    @Test
    fun contactHexOutPathDecodesAsValue() {
        val config = NodeConfigJson.decode(contactJson(outPath = "\"0a0b\""))
        assertEquals("0a0b", config.contacts!!.single().outPath)
    }

    @Test
    fun contactNullVsNonNullCustomName() {
        assertNull(NodeConfigJson.decode(contactJson(customName = "null")).contacts!!.single().customName)
        assertEquals("Nick", NodeConfigJson.decode(contactJson(customName = "\"Nick\"")).contacts!!.single().customName)
    }

    // MARK: Encode

    @Test
    fun contactEncodesNullCustomNameAndOutPathAsExplicitNull() {
        val config =
            MeshCoreNodeConfig(contacts = listOf(contact(customName = null, outPath = null, pathHashMode = null)))
        val encoded = NodeConfigJson.encode(config)

        assertTrue(encoded.contains("\"custom_name\": null"), encoded)
        assertTrue(encoded.contains("\"out_path\": null"), encoded)
        assertFalse(encoded.contains("path_hash_mode"), encoded)
    }

    @Test
    fun contactEncodesNonNullValuesAndPathHashMode() {
        val config =
            MeshCoreNodeConfig(contacts = listOf(contact(customName = "Nick", outPath = "0a0b", pathHashMode = 2u)))
        val encoded = NodeConfigJson.encode(config)

        assertTrue(encoded.contains("\"custom_name\": \"Nick\""), encoded)
        assertTrue(encoded.contains("\"out_path\": \"0a0b\""), encoded)
        assertTrue(encoded.contains("\"path_hash_mode\": 2"), encoded)
    }

    @Test
    fun roundTripPreservesAllFields() {
        val config = MeshCoreNodeConfig(
            name = "N",
            publicKey = "aa",
            radioSettings = MeshCoreNodeConfig.RadioSettings(910525u, 62500u, 7u, 5u, (-9).toByte()),
            positionSettings = MeshCoreNodeConfig.PositionSettings("1.0", "2.0"),
            otherSettings = MeshCoreNodeConfig.OtherSettings(manualAddContacts = 1u, advertLocationPolicy = 2u),
            channels = listOf(MeshCoreNodeConfig.ChannelConfig("P", "cd")),
            contacts = listOf(contact()),
        )
        assertEquals(config, NodeConfigJson.decode(NodeConfigJson.encode(config)))
    }

    @Test
    fun otherSettingsAllSevenFieldsRoundTrip() {
        val other = MeshCoreNodeConfig.OtherSettings(1u, 2u, 3u, 4u, 5u, 6u, 7u)
        val config = MeshCoreNodeConfig(otherSettings = other)
        assertEquals(other, NodeConfigJson.decode(NodeConfigJson.encode(config)).otherSettings)
    }

    // MARK: Position / ConfigSections

    @Test
    fun positionIsZero() {
        assertTrue(MeshCoreNodeConfig.PositionSettings("0", "0").isZero)
        assertTrue(MeshCoreNodeConfig.PositionSettings("0.0", "0.0").isZero)
        assertFalse(MeshCoreNodeConfig.PositionSettings("1.5", "0").isZero)
    }

    @Test
    fun configSectionsSelection() {
        assertFalse(ConfigSections().anySectionSelected)
        assertFalse(ConfigSections().allSelected)
        assertTrue(ConfigSections().selectAll().allSelected)
        assertFalse(ConfigSections(nodeIdentity = true).allSelected)
        assertTrue(ConfigSections(nodeIdentity = true).anySectionSelected)
        assertFalse(ConfigSections().selectAll().deselectAll().anySectionSelected)
    }

    private fun contactJson(customName: String = "null", outPath: String = "null"): String = """
        {"contacts": [{"type": 1, "name": "B", "custom_name": $customName, "public_key": "ff",
          "flags": 0, "latitude": "0", "longitude": "0", "last_advert": 1, "last_modified": 2,
          "out_path": $outPath}]}
    """.trimIndent()
}
