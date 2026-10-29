// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/NodeConfigImportPlannerTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.ChannelConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.ContactConfig
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.crypto.Ed25519Crypto
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.PathEncoding
import com.meshcoreone.android.core.protocol.parser.Parsers
import com.meshcoreone.android.core.services.contacts.toContactFrame
import com.meshcoreone.android.core.services.contacts.toMeshContact
import java.security.SecureRandom
import java.time.Instant
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

class NodeConfigImportPlannerTest {
    private val secretBytesA = Bytes.of(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF)
    private val secretBytesB = Bytes.of(0xFF, 0xEE, 0xDD, 0xCC, 0xBB, 0xAA, 0x99, 0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00)
    private val validChannelSecretA = "00112233445566778899aabbccddeeff"
    private val validChannelSecretB = "ffeeddccbbaa99887766554433221100"
    private val pubKeyHexA = "ab".repeat(32)
    private val pubKeyHexB = "cd".repeat(32)
    private val channelSections = ConfigSections(channels = true)
    private val contactSections = ConfigSections(contacts = true)
    private val identitySections = ConfigSections(nodeIdentity = true)
    private val positionSections = ConfigSections(positionSettings = true)
    private val radioSections = ConfigSections(radioSettings = true)

    private fun contact(
        type: UByte = 1u, name: String, publicKey: String, latitude: String = "0", longitude: String = "0",
        lastModified: UInt = 0u, outPath: String? = null, pathHashMode: UByte? = null,
    ) = ContactConfig(
        type = type, name = name, publicKey = publicKey, flags = 0u, latitude = latitude, longitude = longitude,
        lastAdvert = 0u, lastModified = lastModified, outPath = outPath, pathHashMode = pathHashMode,
    )

    private fun plan(
        sections: ConfigSections, channels: List<ChannelConfig>? = null, contacts: List<ContactConfig>? = null,
        positionSettings: MeshCoreNodeConfig.PositionSettings? = null, radioSettings: MeshCoreNodeConfig.RadioSettings? = null,
        privateKey: String? = null, publicKey: String? = null, name: String? = null, maxChannels: Int = 8,
        maxContacts: Int = 100, maxTxPower: Byte = 30, existingChannels: List<DeviceChannelSlot> = nodeConfigEmptySlots(8),
        existingContacts: Map<String, MeshContact> = emptyMap(),
    ) = nodeConfigPlan(
        MeshCoreNodeConfig(
            name = name, publicKey = publicKey, privateKey = privateKey, radioSettings = radioSettings,
            positionSettings = positionSettings, channels = channels?.let { SnapshotList(it) }, contacts = contacts?.let { SnapshotList(it) },
        ),
        sections, maxChannels, maxContacts, maxTxPower, existingChannels, existingContacts,
    )

    /** A present-key entry whose fields deliberately differ from the capacity imports (no M2 skip). */
    private fun presentContact(hexKey: String) = hexKey to nodeConfigMeshContact(
        id = hexKey, publicKey = nodeConfigBytes(0, 32), outPathLength = 0xFFu, advertisedName = "Existing",
    )

    private fun slots(vararg configured: DeviceChannelSlot): List<DeviceChannelSlot> =
        nodeConfigEmptySlots(8).map { empty -> configured.firstOrNull { it.index == empty.index } ?: empty }

    private fun slot(index: Int, name: String, secret: Bytes) = DeviceChannelSlot(index.toUByte(), name, secret, true)

    private fun validRadio(
        frequency: UInt = 910_525u, bandwidth: UInt = 62_500u, spreadingFactor: UByte = 7u, codingRate: UByte = 5u, txPower: Byte = 20,
    ) = MeshCoreNodeConfig.RadioSettings(frequency, bandwidth, spreadingFactor, codingRate, txPower)

    /** A generated identity: the 64-byte expanded key and its public key (Swift KeyGenerationService). */
    private fun generatedIdentity(): Pair<Bytes, Bytes> {
        val seed = Bytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
        return Ed25519Crypto.expandSeed(seed) to Ed25519Crypto.publicKeyFromSeed(seed)
    }

    /**
     * The device-resident form `getContacts` would report: the record encoded with the add frame and
     * decoded by the live parser, with the device's last_modified appended at offset 143.
     */
    private fun deviceStored(record: MeshContact, lastModified: Instant? = null): MeshContact {
        val frame = PacketBuilder.updateContact(record).slice(1, 144) +
            ByteWriter().appendUInt32LE((lastModified ?: record.lastModified).nodeConfigEpochUInt32()).toBytes()
        return assertNotNull(Parsers.parseContactData(frame))
    }

    private fun recordFor(config: ContactConfig): MeshContact =
        deviceStored(plan(contactSections, contacts = listOf(config)).contactRecords.single())

    private inline fun <reified T : NodeConfigServiceError> rejects(expected: T? = null, block: () -> Unit) {
        val error = assertFailsWith<T> { block() }
        if (expected != null) assertEquals(expected, error)
    }

    @TestFactory
    fun plannerCases() = nodeConfigSourceCases(
        "NodeConfigImportPlannerTests",
        "Position with NaN latitude is rejected before any write" to {
            rejects(NodeConfigServiceError.InvalidCoordinate(CoordinateField.PositionLatitude)) {
                plan(positionSections, positionSettings = MeshCoreNodeConfig.PositionSettings("nan", "0"))
            }
        },
        "Position with out-of-range latitude is rejected" to {
            rejects(NodeConfigServiceError.InvalidCoordinate(CoordinateField.PositionLatitude)) {
                plan(positionSections, positionSettings = MeshCoreNodeConfig.PositionSettings("1000000000", "0"))
            }
        },
        "Contact with infinite longitude is rejected" to {
            rejects(NodeConfigServiceError.InvalidCoordinate(CoordinateField.ContactLongitude("Bad"))) {
                plan(contactSections, contacts = listOf(contact(name = "Bad", publicKey = pubKeyHexA, longitude = "inf")))
            }
        },
        "Valid position passes and is carried into the plan" to {
            val result = plan(positionSections, positionSettings = MeshCoreNodeConfig.PositionSettings("47.6", "-122.3"))
            assertEquals(47.6, result.position?.latitude); assertEquals(-122.3, result.position?.longitude)
        },
        "Two same-name hashtag channels fold onto one slot" to {
            val result = plan(channelSections, channels = listOf(ChannelConfig("#rescue", validChannelSecretA), ChannelConfig("#rescue", validChannelSecretB)))
            assertEquals(2, result.channelWrites.size)
            assertEquals(1, result.channelWrites.map { it.index }.toSet().size, "Same-name channels must not consume two slots")
        },
        "Two same-secret channels fold onto one slot" to {
            val result = plan(channelSections, channels = listOf(ChannelConfig("Alpha", validChannelSecretA), ChannelConfig("Beta", validChannelSecretA)))
            assertEquals(2, result.channelWrites.size)
            assertEquals(1, result.channelWrites.map { it.index }.toSet().size, "Same-secret channels must not consume two slots")
        },
        "Two distinct channels land on two separate empty slots" to {
            val result = plan(channelSections, channels = listOf(ChannelConfig("Alpha", validChannelSecretA), ChannelConfig("Beta", validChannelSecretB)))
            assertEquals(2, result.channelWrites.size)
            assertEquals(2, result.channelWrites.map { it.index }.toSet().size, "Distinct channels must occupy distinct slots")
        },
        "Overwriting a configured slot with a differing secret is flagged" to {
            val result = plan(channelSections, channels = listOf(ChannelConfig("#old", validChannelSecretB)), existingChannels = slots(slot(0, "#old", secretBytesA)))
            assertTrue(result.channelsOverwriteExisting)
            assertEquals(0.toUByte(), result.channelWrites.first().index)
        },
        "Adding a channel into an empty slot is not an overwrite" to {
            assertFalse(plan(channelSections, channels = listOf(ChannelConfig("#new", validChannelSecretA))).channelsOverwriteExisting)
        },
        "Slot exhaustion is rejected before any write" to {
            rejects(NodeConfigServiceError.NoAvailableChannelSlot("New")) {
                plan(channelSections, channels = listOf(ChannelConfig("New", validChannelSecretB)), maxChannels = 1,
                    existingChannels = listOf(slot(0, "Existing", secretBytesA)))
            }
        },
        "Existing hashtag slot folds a same-secret non-hashtag import onto its slot" to {
            val result = plan(channelSections, channels = listOf(ChannelConfig("Alpha", validChannelSecretA)), existingChannels = slots(slot(0, "#rescue", secretBytesA)))
            assertEquals(1, result.channelWrites.size)
            assertEquals(0.toUByte(), result.channelWrites.first().index)
            assertTrue(result.channelsOverwriteExisting)
        },
        "Long hashtag name folds onto its existing slot despite device-side truncation" to {
            val fullName = "#" + "a".repeat(40)
            val deviceTruncated = "#" + "a".repeat(30)
            val result = plan(channelSections, channels = listOf(ChannelConfig(fullName, validChannelSecretB)), existingChannels = slots(slot(0, deviceTruncated, secretBytesA)))
            assertEquals(1, result.channelWrites.size)
            assertEquals(0.toUByte(), result.channelWrites.first().index)
        },
        "Hashtag-name import whose secret already lives on another slot folds onto the secret's slot" to {
            val result = plan(channelSections, channels = listOf(ChannelConfig("#general", validChannelSecretB)),
                existingChannels = slots(slot(0, "#general", secretBytesA), slot(1, "Other", secretBytesB)))
            assertEquals(1, result.channelWrites.size)
            assertEquals(1.toUByte(), result.channelWrites.first().index)
            assertTrue(result.channelWrites.all { it.index != 0.toUByte() })
        },
        "Non-canonical secret hex still dedups against the canonically-keyed existing slot" to {
            val result = plan(channelSections, channels = listOf(ChannelConfig("Renamed", validChannelSecretA.uppercase())),
                existingChannels = slots(slot(0, "Existing", secretBytesA)))
            assertEquals(1, result.channelWrites.size)
            assertEquals(0.toUByte(), result.channelWrites.first().index)
        },
        "Invalid channel secret length is rejected" to {
            rejects(NodeConfigServiceError.InvalidChannelSecret(0, 4)) { plan(channelSections, channels = listOf(ChannelConfig("Bad", "abcd"))) }
        },
        "Channel secret with trailing non-hex characters is rejected, not silently accepted" to {
            rejects(NodeConfigServiceError.InvalidChannelSecret(0, 34)) { plan(channelSections, channels = listOf(ChannelConfig("Bad", validChannelSecretA + "zz"))) }
        },
        "Present-but-garbage private key is rejected, not silently skipped" to {
            rejects(NodeConfigServiceError.InvalidPrivateKey(4)) { plan(identitySections, privateKey = "zzzz") }
        },
        "Wrong-length private key is rejected" to {
            rejects(NodeConfigServiceError.InvalidPrivateKey(4)) { plan(identitySections, privateKey = "abcd") }
        },
        "Valid 64-byte expanded private key without a public key is accepted" to {
            val (expanded, _) = generatedIdentity()
            assertEquals(expanded, plan(identitySections, privateKey = expanded.hexString).importPrivateKey)
        },
        "Expanded private key with its public key round-trips and is accepted" to {
            val (expanded, publicKey) = generatedIdentity()
            assertEquals(expanded, plan(identitySections, privateKey = expanded.hexString, publicKey = publicKey.hexString).importPrivateKey)
        },
        "Valid radio settings within firmware ranges are carried into the plan" to {
            assertEquals(validRadio(), plan(radioSections, radioSettings = validRadio()).radioSettings)
        },
        "Out-of-range spreading factor is rejected before any write" to {
            rejects(NodeConfigServiceError.InvalidRadioSettings(RadioField.SPREADING_FACTOR)) { plan(radioSections, radioSettings = validRadio(spreadingFactor = 99u)) }
        },
        "Frequency below the firmware floor is rejected" to {
            rejects(NodeConfigServiceError.InvalidRadioSettings(RadioField.FREQUENCY)) { plan(radioSections, radioSettings = validRadio(frequency = 100_000u)) }
        },
        "TX power above the device's reported maximum is rejected" to {
            rejects(NodeConfigServiceError.InvalidRadioSettings(RadioField.TX_POWER)) { plan(radioSections, radioSettings = validRadio(txPower = 25), maxTxPower = 20) }
        },
        "TX power up to the device's reported maximum is accepted" to {
            assertEquals(30.toByte(), plan(radioSections, radioSettings = validRadio(txPower = 30), maxTxPower = 30).radioSettings?.txPower)
        },
        "Duplicate contacts dedup by public key, newest last_modified wins" to {
            val result = plan(contactSections, contacts = listOf(
                contact(name = "Older", publicKey = pubKeyHexA, lastModified = 100u), contact(name = "Newer", publicKey = pubKeyHexA, lastModified = 200u),
            ))
            assertEquals(listOf("Newer"), result.contactRecords.map { it.advertisedName })
        },
        "Dedup keeps the newer record even when it appears first (inverse comparison branch)" to {
            val result = plan(contactSections, contacts = listOf(
                contact(name = "Newer", publicKey = pubKeyHexA, lastModified = 200u), contact(name = "Older", publicKey = pubKeyHexA, lastModified = 100u),
            ))
            assertEquals(listOf("Newer"), result.contactRecords.map { it.advertisedName })
        },
        "Dedup of equal-timestamp duplicates collapses to one record deterministically" to {
            val result = plan(contactSections, contacts = listOf(
                contact(name = "First", publicKey = pubKeyHexA, lastModified = 100u), contact(name = "Second", publicKey = pubKeyHexA, lastModified = 100u),
            ))
            assertEquals(listOf("Second"), result.contactRecords.map { it.advertisedName })
        },
        "Contact with a valid out_path but out-of-range path hash mode is rejected" to {
            rejects(NodeConfigServiceError.InvalidPathHashMode("BadMode", 3u)) {
                plan(contactSections, contacts = listOf(contact(name = "BadMode", publicKey = pubKeyHexA, outPath = "aabb", pathHashMode = 3u)))
            }
        },
        "Exceeding device contact capacity is rejected" to {
            rejects(NodeConfigServiceError.ContactCapacityExceeded(2, 1)) {
                plan(contactSections, contacts = listOf(contact(name = "A", publicKey = pubKeyHexA), contact(name = "B", publicKey = pubKeyHexB)), maxContacts = 1)
            }
        },
        "Exactly filling the remaining contact slots is accepted" to {
            val result = plan(contactSections, contacts = listOf(contact(name = "A", publicKey = pubKeyHexA), contact(name = "B", publicKey = pubKeyHexB)), maxContacts = 2)
            assertEquals(2, result.contactRecords.size)
        },
        "Capacity check credits keys already on the device (updates consume no slot)" to {
            val result = plan(contactSections, contacts = listOf(contact(name = "Update", publicKey = pubKeyHexA)), maxContacts = 1,
                existingContacts = mapOf(presentContact(pubKeyHexA.lowercase())))
            assertEquals(1, result.contactRecords.size)
        },
        "Occupancy that includes a virtual extra key blocks update-only until the key is excluded" to {
            val withVirtual = mapOf(presentContact(pubKeyHexA.lowercase()), presentContact(pubKeyHexB.lowercase()))
            rejects<NodeConfigServiceError.ContactCapacityExceeded> {
                plan(contactSections, contacts = listOf(contact(name = "Update", publicKey = pubKeyHexA)), maxContacts = 1, existingContacts = withVirtual)
            }
            plan(contactSections, contacts = listOf(contact(name = "Update", publicKey = pubKeyHexA)), maxContacts = 1,
                existingContacts = withVirtual - pubKeyHexB.lowercase())
        },
        "A new contact has no free slot once the device table is full" to {
            rejects(NodeConfigServiceError.ContactCapacityExceeded(1, 0)) {
                plan(contactSections, contacts = listOf(contact(name = "New", publicKey = pubKeyHexB)), maxContacts = 1,
                    existingContacts = mapOf(presentContact(pubKeyHexA.lowercase())))
            }
        },
        "Invalid out_path hex is rejected instead of silently downgrading to direct" to {
            rejects(NodeConfigServiceError.InvalidOutPath("BadPath")) {
                plan(contactSections, contacts = listOf(contact(name = "BadPath", publicKey = pubKeyHexA, outPath = "zzz")))
            }
        },
        "Out_path longer than the firmware buffer is rejected" to {
            // 66 bytes of 3-byte hashes = 22 hops: within the hop field but past MAX_PATH_SIZE.
            val longPath = "ab".repeat(PathEncoding.MAX_PATH_BYTES / 3 * 3 + 3)
            rejects(NodeConfigServiceError.InvalidOutPath("TooLong")) {
                plan(contactSections, contacts = listOf(contact(name = "TooLong", publicKey = pubKeyHexA, outPath = longPath, pathHashMode = 2u)))
            }
        },
        "Invalid contact public key is rejected" to {
            rejects(NodeConfigServiceError.InvalidContactPublicKey("BadKey")) { plan(contactSections, contacts = listOf(contact(name = "BadKey", publicKey = "abcd"))) }
        },
        "Unknown contact type byte is preserved verbatim while UI type falls back to chat" to {
            val record = plan(contactSections, contacts = listOf(contact(type = 99u, name = "FutureType", publicKey = pubKeyHexA))).contactRecords.single()
            assertEquals(99.toUByte(), record.typeRawValue)
            assertEquals(com.meshcoreone.android.core.protocol.model.ContactType.CHAT, record.type)
        },
        "Absent out_path plans flood routing; empty string plans direct" to {
            assertEquals(0xFF.toUByte(), plan(contactSections, contacts = listOf(contact(name = "Flood", publicKey = pubKeyHexA))).contactRecords.first().outPathLength)
            assertEquals(0.toUByte(), plan(contactSections, contacts = listOf(contact(name = "Direct", publicKey = pubKeyHexB, outPath = ""))).contactRecords.first().outPathLength)
        },
        "Valid out_path resolves to its bytes and encoded length for each hash mode" to {
            val cases = listOf(
                Triple(0, "aabb", Bytes.of(0xAA, 0xBB)) to 0x02,
                Triple(1, "aabbccdd", Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)) to 0x42,
                Triple(2, "aabbccddeeff", Bytes.of(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF)) to 0x82,
            )
            for ((input, length) in cases) {
                val (mode, hex, bytes) = input
                val record = plan(contactSections, contacts = listOf(contact(name = "Routed", publicKey = pubKeyHexA, outPath = hex, pathHashMode = mode.toUByte()))).contactRecords.single()
                assertEquals(bytes, record.outPath)
                assertEquals(length.toUByte(), record.outPathLength)
            }
        },
        "Empty-but-present channel and contact arrays plan no writes and no overwrite" to {
            val result = plan(ConfigSections(channels = true, contacts = true), channels = emptyList(), contacts = emptyList())
            assertTrue(result.channelWrites.isEmpty()); assertTrue(result.contactRecords.isEmpty()); assertFalse(result.channelsOverwriteExisting)
        },
        "Identity plan carries a non-nil node name verbatim" to {
            assertEquals("Rescue Base", plan(identitySections, name = "Rescue Base").nodeName)
        },
        "A channel byte-identical to its resolved slot plans no write" to {
            val result = plan(channelSections, channels = listOf(ChannelConfig("Alpha", validChannelSecretA)), existingChannels = slots(slot(0, "Alpha", secretBytesA)))
            assertTrue(result.channelWrites.isEmpty(), "An identical slot must not re-commit /channels2")
            assertFalse(result.channelsOverwriteExisting)
        },
        "A name-only diff, secret-only diff, and secret relocation each still plan one write" to {
            assertEquals(1, plan(channelSections, channels = listOf(ChannelConfig("New", validChannelSecretA)), existingChannels = slots(slot(0, "Old", secretBytesA))).channelWrites.size)
            assertEquals(1, plan(channelSections, channels = listOf(ChannelConfig("Alpha", validChannelSecretB)), existingChannels = slots(slot(0, "Alpha", secretBytesA))).channelWrites.size)
            val relocate = plan(channelSections, channels = listOf(ChannelConfig("Renamed", validChannelSecretA)), existingChannels = slots(slot(2, "Alpha", secretBytesA)))
            assertEquals(1, relocate.channelWrites.size)
            assertEquals(2.toUByte(), relocate.channelWrites.first().index)
        },
        "A brand-new channel into an empty slot still plans one write" to {
            assertEquals(1, plan(channelSections, channels = listOf(ChannelConfig("#new", validChannelSecretA))).channelWrites.size)
        },
        "A duplicate restoring a slot an earlier write changed is not swallowed by the skip" to {
            val hashtag = plan(channelSections, channels = listOf(ChannelConfig("#general", validChannelSecretB), ChannelConfig("#general", validChannelSecretA)),
                existingChannels = slots(slot(0, "#general", secretBytesA)))
            assertEquals(2, hashtag.channelWrites.size)
            assertEquals(secretBytesA, hashtag.channelWrites.last().secret, "The restoring write must win, not be dropped")
            val secretFold = plan(channelSections, channels = listOf(ChannelConfig("Bar", validChannelSecretA), ChannelConfig("Foo", validChannelSecretA)),
                existingChannels = slots(slot(0, "Foo", secretBytesA)))
            assertEquals(2, secretFold.channelWrites.size)
            assertEquals("Foo", secretFold.channelWrites.last().name, "The restoring write must win, not be dropped")
        },
        "A contact equal on all persisted fields is dropped" to {
            val config = contact(name = "Bravo", publicKey = pubKeyHexA, latitude = "47.5", longitude = "-122.5", lastModified = 1000u, outPath = "aabb", pathHashMode = 0u)
            val existing = recordFor(config)
            assertTrue(plan(contactSections, contacts = listOf(config), existingContacts = mapOf(existing.id to existing)).contactRecords.isEmpty())
        },
        "A diff in any single persisted field still emits the contact" to {
            val base = contact(name = "Bravo", publicKey = pubKeyHexA, latitude = "47.5", longitude = "-122.5", lastModified = 1000u, outPath = "aabb", pathHashMode = 0u)
            val existing = recordFor(base)
            val variants = listOf(
                "type" to base.copy(type = 2u), "name" to base.copy(name = "Charlie"), "path" to base.copy(outPath = "ccdd"),
                "coords" to base.copy(latitude = "48.0"), "lastModified" to base.copy(lastModified = 2000u),
            )
            for ((label, variant) in variants) {
                val result = plan(contactSections, contacts = listOf(variant), existingContacts = mapOf(existing.id to existing))
                if (label == "lastModified") {
                    // Approved Android adaptation: reconcile a timestamp-only difference locally.
                    assertTrue(result.contactRecords.isEmpty())
                    assertSame(existing, result.contactActions.single().contact)
                } else {
                    assertEquals(1, result.contactRecords.size, "A $label diff must still write the contact")
                }
            }
        },
        "A name differing only past the firmware field width is treated as equal and dropped" to {
            val existing = recordFor(contact(name = "#" + "a".repeat(30), publicKey = pubKeyHexA))
            val result = plan(contactSections, contacts = listOf(contact(name = "#" + "a".repeat(30) + "EXTRA", publicKey = pubKeyHexA)),
                existingContacts = mapOf(existing.id to existing))
            assertTrue(result.contactRecords.isEmpty())
        },
        "A new contact is unaffected by the skip and capacity stays correct" to {
            val match = contact(name = "Match", publicKey = pubKeyHexA, lastModified = 5u)
            val existing = recordFor(match)
            val result = plan(contactSections, contacts = listOf(match, contact(name = "Fresh", publicKey = pubKeyHexB, lastModified = 5u)),
                existingContacts = mapOf(existing.id to existing))
            assertEquals(listOf("Fresh"), result.contactRecords.map { it.advertisedName })
        },
        "A contact the firmware re-stamped is re-emitted, not dropped (skip is safe-fail)" to {
            val config = contact(name = "Bravo", publicKey = pubKeyHexA, latitude = "47.5", longitude = "-122.5", lastModified = 1000u, outPath = "aabb", pathHashMode = 0u)
            val existing = deviceStored(plan(contactSections, contacts = listOf(config)).contactRecords.single(), Instant.ofEpochSecond(9999))
            // The original case name is retained for source traceability; Android deliberately reconciles instead.
            val result = plan(contactSections, contacts = listOf(config), existingContacts = mapOf(existing.id to existing))
            assertTrue(result.contactRecords.isEmpty())
            assertSame(existing, result.contactActions.single().contact)
        },
        "Unchanged deduplicated contacts reconcile the actual device copy in import order" to {
            val matching = contact(name = "Existing", publicKey = pubKeyHexA, lastModified = 100u)
            val device = recordFor(matching).copy(lastAdvertisement = Instant.ofEpochSecond(777))
            val result = plan(
                contactSections,
                contacts = listOf(matching, matching, contact(name = "New", publicKey = pubKeyHexB)),
                existingContacts = mapOf(device.id to device),
            )
            assertEquals(listOf(false, true), result.contactActions.map { it.writeDevice })
            assertSame(device, result.contactActions.first().contact)
            assertEquals(listOf("New"), result.contactRecords.map { it.advertisedName })
        },
    )

    private fun encoderContact(
        lastAdvertisement: Instant = Instant.ofEpochSecond(1_700_000_000), latitude: Double = 0.0, longitude: Double = 0.0,
        typeRawValue: UByte = 1u,
    ) = nodeConfigMeshContact(
        id = "c", publicKey = nodeConfigBytes(1, 32),
        type = com.meshcoreone.android.core.protocol.model.ContactType.fromRawValue(typeRawValue)
            ?: com.meshcoreone.android.core.protocol.model.ContactType.CHAT,
        typeRawValue = typeRawValue, advertisedName = "T", lastAdvertisement = lastAdvertisement,
        latitude = latitude, longitude = longitude, lastModified = Instant.now(),
    )

    @TestFactory
    fun encoderCases() = nodeConfigSourceCases(
        "PacketBuilderEncoderTests",
        "setCoordinates clamps NaN to zero instead of trapping" to {
            val data = PacketBuilder.setCoordinates(Double.NaN, Double.NaN)
            assertEquals(0, data.readInt32LE(1)); assertEquals(0, data.readInt32LE(5))
        },
        "setCoordinates clamps out-of-range degrees to the valid bounds" to {
            val data = PacketBuilder.setCoordinates(9999.0, -9999.0)
            assertEquals(90_000_000, data.readInt32LE(1)); assertEquals(-180_000_000, data.readInt32LE(5))
        },
        "setCoordinates handles infinity without trapping" to {
            val data = PacketBuilder.setCoordinates(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            assertEquals(0, data.readInt32LE(1)); assertEquals(0, data.readInt32LE(5))
        },
        "updateContact saturates a pre-1970 advertisement timestamp to zero" to {
            assertEquals(0u, PacketBuilder.updateContact(encoderContact(lastAdvertisement = Instant.ofEpochSecond(-1_000_000))).readUInt32LE(132))
        },
        "updateContact saturates a post-2106 advertisement timestamp to UInt32.max" to {
            val farFuture = Instant.ofEpochSecond(UInt.MAX_VALUE.toLong() + 1_000_000)
            assertEquals(UInt.MAX_VALUE, PacketBuilder.updateContact(encoderContact(lastAdvertisement = farFuture)).readUInt32LE(132))
        },
        "updateContact clamps out-of-range coordinates without trapping" to {
            val data = PacketBuilder.updateContact(encoderContact(latitude = Double.NaN, longitude = 9999.0))
            assertEquals(0, data.readInt32LE(136)); assertEquals(180_000_000, data.readInt32LE(140))
        },
        "updateContact emits a 147-byte frame" to {
            assertEquals(147, PacketBuilder.updateContact(encoderContact()).size)
        },
        "updateContact writes the raw type byte verbatim for modeled and unmodeled types" to {
            for (raw in listOf(0x01, 0x02, 0x03, 0x04, 0x99)) {
                assertEquals(raw.toUByte(), PacketBuilder.updateContact(encoderContact(typeRawValue = raw.toUByte()))[33])
            }
        },
    )

    private fun contactBytes(typeByte: Int): Bytes = Bytes(ByteArray(147).also { data ->
        for (i in 0 until 32) data[i] = 0xAB.toByte()
        data[32] = typeByte.toByte()
        data[34] = 0xFF.toByte()
    })

    @TestFactory
    fun typeByteCases() = nodeConfigSourceCases(
        "ContactTypeBytePreservationTests",
        "parseContactData preserves an unmodeled type byte while falling back to .chat" to {
            val contact = assertNotNull(Parsers.parseContactData(contactBytes(0x04)))
            assertEquals(0x04.toUByte(), contact.typeRawValue)
            assertEquals(com.meshcoreone.android.core.protocol.model.ContactType.CHAT, contact.type)
        },
        "parseContactData keeps modeled type bytes and their enum in sync" to {
            for ((raw, expected) in listOf(1 to "CHAT", 2 to "REPEATER", 3 to "ROOM")) {
                val contact = assertNotNull(Parsers.parseContactData(contactBytes(raw)))
                assertEquals(raw.toUByte(), contact.typeRawValue)
                assertEquals(expected, contact.type.name)
            }
        },
        "ContactFrame ↔ MeshContact ↔ Contact/ContactDTO and export all keep 0x04" to {
            val frame = com.meshcoreone.android.core.model.ContactFrame(
                publicKey = nodeConfigBytes(0xAB, 32), type = com.meshcoreone.android.core.protocol.model.ContactType.CHAT,
                flags = 0u, outPathLength = 0xFFu, outPath = Bytes.EMPTY, name = "FutureType", lastAdvertTimestamp = 0u,
                latitude = 0.0, longitude = 0.0, lastModified = 0u, typeRawValue = 0x04u,
            )
            // Frame -> MeshContact (Swift ContactFrame.toMeshContact) -> Frame (the import's local-save conversion).
            val meshContact = nodeConfigMeshContact(
                publicKey = frame.publicKey, type = frame.type, typeRawValue = frame.typeRawValue, flags = frame.flags,
                outPathLength = frame.outPathLength, outPath = frame.outPath, advertisedName = frame.name,
            )
            assertEquals(0x04.toUByte(), meshContact.typeRawValue)
            assertEquals(0x04.toUByte(), meshContact.nodeConfigContactFrame().typeRawValue)
            // Frame -> persisted contact DTO -> Frame (Android has no SwiftData @Model; the DTO is the persisted form).
            val dto = com.meshcoreone.android.core.model.ContactDTO(
                radioId = com.meshcoreone.android.core.model.RadioId(java.util.UUID.randomUUID()), publicKey = frame.publicKey,
                name = "", lastHeardTimestamp = null,
            ).updating(frame)
            assertEquals(0x04.toUByte(), dto.typeRawValue)
            assertEquals(0x04.toUByte(), dto.toContactFrame().typeRawValue)
            // Export re-emits the raw byte.
            assertEquals(0x04.toUByte(), NodeConfigService.buildContactConfig(meshContact).type)
        },
        "Export and decode stay byte-identical for modeled types" to {
            for (raw in 1..3) {
                val type = assertNotNull(com.meshcoreone.android.core.protocol.model.ContactType.fromRawValue(raw.toUByte()))
                val meshContact = nodeConfigMeshContact(id = "m", publicKey = nodeConfigBytes(0xAB, 32), type = type, typeRawValue = raw.toUByte(), advertisedName = "M")
                assertEquals(raw.toUByte(), NodeConfigService.buildContactConfig(meshContact).type)
            }
        },
    )

    @TestFactory
    fun realContactConversionCases() = nodeConfigNativeCases(
        "actual contact conversions preserve the unmodeled type byte through export" to {
            val frame = com.meshcoreone.android.core.model.ContactFrame(
                publicKey = nodeConfigBytes(0xAB, 32), type = com.meshcoreone.android.core.protocol.model.ContactType.CHAT,
                flags = 0u, outPathLength = 0xFFu, outPath = Bytes.EMPTY, name = "FutureType", lastAdvertTimestamp = 0u,
                latitude = 0.0, longitude = 0.0, lastModified = 0u, typeRawValue = 0x04u,
            )
            val meshContact = frame.toMeshContact()
            assertEquals(0x04u.toUByte(), meshContact.typeRawValue)
            assertEquals(0x04u.toUByte(), meshContact.toContactFrame().typeRawValue)
            assertEquals(0x04u.toUByte(), meshContact.nodeConfigContactFrame().typeRawValue)
            val dto = com.meshcoreone.android.core.model.ContactDTO.fromFrame(
                com.meshcoreone.android.core.model.RadioId(java.util.UUID.randomUUID()), frame,
            )
            assertEquals(0x04u.toUByte(), dto.typeRawValue)
            assertEquals(0x04u.toUByte(), dto.toContactFrame().typeRawValue)
            assertEquals(0x04u.toUByte(), NodeConfigService.buildContactConfig(meshContact).type)
        },
    )

    /** Oracle: swiftc on macOS 26 — `"#caf\u{E9}" == "#cafe\u{301}"` is true and `"#\u{301}abc".hasPrefix("#")` is false. */
    @TestFactory
    fun canonicalNameCases() = nodeConfigNativeCases(
        "a decomposed device hashtag name folds onto its slot like Swift's canonical String equality" to {
            val result = plan(
                channelSections,
                channels = listOf(ChannelConfig("#caf\u00E9", validChannelSecretB)),
                existingChannels = slots(slot(3, "#cafe\u0301", secretBytesA)),
            )
            assertEquals(listOf(3.toUByte()), result.channelWrites.map { it.index })
            assertTrue(result.channelsOverwriteExisting)
        },
        "a canonically equal name with the same secret is a no-op" to {
            val result = plan(
                channelSections,
                channels = listOf(ChannelConfig("#caf\u00E9", validChannelSecretA)),
                existingChannels = slots(slot(3, "#cafe\u0301", secretBytesA)),
            )
            assertTrue(result.channelWrites.isEmpty())
            assertFalse(result.channelsOverwriteExisting)
        },
        "a hash followed by a combining mark is not a hashtag, so it never folds by name" to {
            val result = plan(
                channelSections,
                channels = listOf(ChannelConfig("#\u0301abc", validChannelSecretB)),
                existingChannels = slots(slot(3, "#\u0301abc", secretBytesA)),
            )
            assertEquals(1, result.channelWrites.size)
            assertNotEquals(3.toUByte(), result.channelWrites.single().index)
            assertFalse(result.channelsOverwriteExisting)
        },
        "compatibility-equivalent names stay distinct, as in Swift" to {
            val result = plan(
                channelSections,
                channels = listOf(ChannelConfig("#\uFB01re", validChannelSecretB)),
                existingChannels = slots(slot(3, "#fire", secretBytesA)),
            )
            assertNotEquals(3.toUByte(), result.channelWrites.single().index)
        },
        "the node-name gate compares canonically" to {
            assertFalse(nodeNameNeedsWrite("Caf\u00E9", nodeConfigSelfInfo(name = "Cafe\u0301")))
            assertTrue(nodeNameNeedsWrite("Cafe", nodeConfigSelfInfo(name = "Cafe\u0301")))
        },
    )
}
