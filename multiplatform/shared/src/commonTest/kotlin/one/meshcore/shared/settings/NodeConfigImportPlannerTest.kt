@file:Suppress("LongParameterList")

package one.meshcore.shared.settings

import one.meshcore.shared.model.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Validation, channel-merge, contact-dedup, and out_path resolution for [planConfigImport], mirroring the
 * behaviors the Swift `NodeConfigImportPlannerTests` cover: radio/coordinate/private-key range rejection,
 * secret-keyed channel single-homing with hashtag/empty-slot fallback + overwrite/skip, contact dedup by
 * public key with capacity and byte-for-byte drop, and the three-state out_path (flood/direct/routed).
 */
class NodeConfigImportPlannerTest {
    private val secretA = "ab".repeat(16)
    private val secretB = "cd".repeat(16)
    private val pubKey = "11".repeat(32)

    private fun plan(
        config: MeshCoreNodeConfig,
        sections: ConfigSections,
        maxChannels: Int = 8,
        maxContacts: Int = 100,
        maxTxPower: Byte = 22,
        existingChannels: List<DeviceChannelSlot> = emptyList(),
        existingContacts: Map<String, ExistingContact> = emptyMap(),
    ) = planConfigImport(
        config,
        sections,
        ConfigImportDevice(maxChannels, maxContacts, maxTxPower, existingChannels, existingContacts),
    )

    private fun radio(freq: UInt = 910_525u, bw: UInt = 62_500u, sf: UByte = 7u, cr: UByte = 5u, tx: Byte = 20) =
        MeshCoreNodeConfig.RadioSettings(freq, bw, sf, cr, tx)

    private fun contact(
        publicKey: String = pubKey,
        lastModified: UInt = 100u,
        outPath: String? = null,
        pathHashMode: UByte? = null,
        latitude: String = "1.0",
        name: String = "Bob",
    ) = MeshCoreNodeConfig.ContactConfig(
        type = 1u,
        name = name,
        publicKey = publicKey,
        flags = 0u,
        latitude = latitude,
        longitude = "2.0",
        lastAdvert = 5u,
        lastModified = lastModified,
        outPath = outPath,
        pathHashMode = pathHashMode,
    )

    private fun slot(index: Int, secret: String, name: String = "ch$index", configured: Boolean = true) =
        DeviceChannelSlot(index, name, requireNotNull(decodeHex(secret)), configured)

    private fun contactsConfig(vararg c: MeshCoreNodeConfig.ContactConfig) = MeshCoreNodeConfig(contacts = c.toList())

    private val contactsOnly = ConfigSections(contacts = true)

    // MARK: Radio

    @Test
    fun validRadioIsPlanned() {
        val p = plan(MeshCoreNodeConfig(radioSettings = radio()), ConfigSections(radioSettings = true))
        assertEquals(910_525u, p.radioSettings?.frequency)
    }

    @Test
    fun radioRangeRejections() {
        val cases = listOf(
            radio(freq = 1u) to RadioField.FREQUENCY,
            radio(bw = 1u) to RadioField.BANDWIDTH,
            radio(sf = 20u) to RadioField.SPREADING_FACTOR,
            radio(cr = 20u) to RadioField.CODING_RATE,
            radio(tx = 100) to RadioField.TX_POWER,
        )
        for ((r, field) in cases) {
            val e = assertFailsWith<NodeConfigError.InvalidRadioSettings> {
                plan(MeshCoreNodeConfig(radioSettings = r), ConfigSections(radioSettings = true))
            }
            assertEquals(field, e.field)
        }
    }

    @Test
    fun txPowerAboveDeviceMaxRejected() {
        val e = assertFailsWith<NodeConfigError.InvalidRadioSettings> {
            plan(
                MeshCoreNodeConfig(radioSettings = radio(tx = 21)),
                ConfigSections(radioSettings = true),
                maxTxPower = 20,
            )
        }
        assertEquals(RadioField.TX_POWER, e.field)
    }

    // MARK: Coordinates + private key

    @Test
    fun positionOutOfRangeAndNonNumericRejected() {
        assertFailsWith<NodeConfigError.InvalidCoordinate> {
            plan(
                MeshCoreNodeConfig(positionSettings = MeshCoreNodeConfig.PositionSettings("91.0", "0")),
                ConfigSections(positionSettings = true),
            )
        }
        assertFailsWith<NodeConfigError.InvalidCoordinate> {
            plan(
                MeshCoreNodeConfig(positionSettings = MeshCoreNodeConfig.PositionSettings("x", "0")),
                ConfigSections(positionSettings = true),
            )
        }
    }

    @Test
    fun privateKeyValidatedByLengthAndHex() {
        val valid = "ab".repeat(64)
        val p = plan(MeshCoreNodeConfig(privateKey = valid, name = "N"), ConfigSections(nodeIdentity = true))
        assertEquals(64, p.importPrivateKey?.size)
        assertEquals("N", p.nodeName)

        assertFailsWith<NodeConfigError.InvalidPrivateKey> {
            plan(MeshCoreNodeConfig(privateKey = "ab"), ConfigSections(nodeIdentity = true))
        }
        assertFailsWith<NodeConfigError.InvalidPrivateKey> {
            plan(MeshCoreNodeConfig(privateKey = "zz".repeat(64)), ConfigSections(nodeIdentity = true))
        }
    }

    // MARK: Channels

    @Test
    fun channelInsertsAtFirstEmptySlot() {
        val config = MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("New", secretA)))
        val existing = listOf(slot(0, secretB), slot(1, "00".repeat(16), configured = false))
        val p = plan(config, ConfigSections(channels = true), existingChannels = existing)
        assertEquals(1, p.channelWrites.single().index)
        assertFalse(p.channelsOverwriteExisting)
    }

    @Test
    fun channelMergesBySecretAndFlagsOverwriteOnNameChange() {
        val config = MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("Renamed", secretA)))
        val existing = listOf(slot(3, secretA, name = "Original"))
        val p = plan(config, ConfigSections(channels = true), existingChannels = existing)
        assertEquals(3, p.channelWrites.single().index)
        assertTrue(p.channelsOverwriteExisting)
    }

    @Test
    fun skipsWriteWhenSlotAlreadyMatches() {
        val config = MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("Same", secretA)))
        val existing = listOf(slot(2, secretA, name = "Same"))
        val p = plan(config, ConfigSections(channels = true), existingChannels = existing)
        assertTrue(p.channelWrites.isEmpty())
        assertFalse(p.channelsOverwriteExisting)
    }

    @Test
    fun hashtagNameMergesToExistingHashtagSlot() {
        val config = MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("#news", secretB)))
        val existing = listOf(slot(4, secretA, name = "#news"))
        val p = plan(config, ConfigSections(channels = true), existingChannels = existing)
        assertEquals(4, p.channelWrites.single().index)
    }

    @Test
    fun intraImportDuplicateSecretFoldsToOneSlot() {
        // Two identical same-secret channels: the second folds onto the first slot and is skipped.
        val config = MeshCoreNodeConfig(
            channels = listOf(
                MeshCoreNodeConfig.ChannelConfig("A", secretA),
                MeshCoreNodeConfig.ChannelConfig("A", secretA),
            ),
        )
        val empties = (0 until 4).map { slot(it, "00".repeat(16), configured = false) }
        val p = plan(config, ConfigSections(channels = true), existingChannels = empties)
        assertEquals(1, p.channelWrites.size)
        assertEquals(0, p.channelWrites.single().index)
    }

    @Test
    fun noFreeChannelSlotRejected() {
        val config = MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("X", secretA)))
        val existing = listOf(slot(0, secretB), slot(1, "ef".repeat(16)))
        assertFailsWith<NodeConfigError.NoAvailableChannelSlot> {
            plan(config, ConfigSections(channels = true), maxChannels = 2, existingChannels = existing)
        }
    }

    @Test
    fun invalidChannelSecretRejected() {
        val config = MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("X", "abcd")))
        val e = assertFailsWith<NodeConfigError.InvalidChannelSecret> { plan(config, ConfigSections(channels = true)) }
        assertEquals(0, e.index)
        assertEquals(4, e.hexLength)
    }

    // MARK: Contacts

    @Test
    fun contactDedupByKeyNewestWins() {
        val config = MeshCoreNodeConfig(
            contacts = listOf(
                contact(lastModified = 100u, name = "Old"),
                contact(lastModified = 200u, name = "New"),
            ),
        )
        val p = plan(config, ConfigSections(contacts = true))
        assertEquals("New", p.contactRecords.single().advertisedName)
    }

    @Test
    fun contactCapacityCountsOnlyNewKeys() {
        val config = contactsConfig(contact(publicKey = "22".repeat(32)))
        assertFailsWith<NodeConfigError.ContactCapacityExceeded> {
            plan(config, contactsOnly, maxContacts = 1, existingContacts = mapOf("11".repeat(32) to existingContact()))
        }
    }

    @Test
    fun unchangedContactIsDropped() {
        val record = plan(contactsConfig(contact()), contactsOnly).contactRecords.single()
        val existing = mapOf(
            record.id to ExistingContact(
                typeRawValue = record.typeRawValue,
                flags = record.flags,
                outPathLength = record.outPathLength,
                outPath = record.outPath,
                advertisedName = record.advertisedName,
                lastModified = record.lastModified,
                latitude = record.latitude,
                longitude = record.longitude,
            ),
        )
        val p = plan(contactsConfig(contact()), contactsOnly, existingContacts = existing)
        assertTrue(p.contactRecords.isEmpty())
    }

    @Test
    fun invalidContactPublicKeyRejected() {
        assertFailsWith<NodeConfigError.InvalidContactPublicKey> {
            plan(contactsConfig(contact(publicKey = "ab")), contactsOnly)
        }
    }

    // MARK: out_path

    @Test
    fun outPathThreeStates() {
        val flood = plan(contactsConfig(contact(outPath = null)), contactsOnly)
        assertEquals(NodeConfigLimits.FLOOD_PATH_SENTINEL, flood.contactRecords.single().outPathLength)

        val direct = plan(contactsConfig(contact(outPath = "")), contactsOnly)
        assertEquals(0u.toUByte(), direct.contactRecords.single().outPathLength)

        val routed = plan(contactsConfig(contact(outPath = "0a0b", pathHashMode = 0u)), contactsOnly)
        val record = routed.contactRecords.single()
        assertEquals(2, record.outPath.size)
        assertEquals(2, record.outPathLength.toInt()) // hashSize 1, hopCount 2 -> (0 shl 6) or 2
    }

    @Test
    fun invalidOutPathAndPathHashModeRejected() {
        assertFailsWith<NodeConfigError.InvalidOutPath> {
            plan(contactsConfig(contact(outPath = "zzz")), contactsOnly)
        }
        assertFailsWith<NodeConfigError.InvalidPathHashMode> {
            plan(contactsConfig(contact(outPath = "0a0b", pathHashMode = 3u)), contactsOnly)
        }
    }

    @Test
    fun unselectedSectionsAreNotPlanned() {
        val config = MeshCoreNodeConfig(radioSettings = radio(freq = 1u), name = "N")
        val p = plan(config, ConfigSections())
        assertNull(p.radioSettings)
        assertNull(p.nodeName)
    }

    private fun existingContact() =
        ExistingContact(1u, 0u, NodeConfigLimits.FLOOD_PATH_SENTINEL, ByteArray(0), "X", 1L, 0.0, 0.0)
}
