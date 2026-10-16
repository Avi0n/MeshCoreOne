package one.meshcore.shared.settings

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.VContactIdentity
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NodeConfigService] export and import over a scripted [NodeConfigSession] fake: the export round-trips
 * through [NodeConfigJson], a hardened-firmware key export degrades to a keyless identity, import applies the
 * planned writes in identity-first order (reconciling the radioID via [NodeConfigService.onPostIdentityImport]),
 * [NodeConfigService.previewImport] surfaces the channel-overwrite flag, and the other-params merge fills,
 * skips a no-op, and forwards an unmodeled policy byte verbatim.
 */
class NodeConfigServiceTest {
    // Fixture builder mirrors the full SelfInfo surface used by other-params merge tests.
    @Suppress("LongParameterList")
    private fun selfInfo(
        name: String = "TestNode",
        manualAddContacts: Boolean = false,
        advertisementLocationPolicy: Int = 1,
        telemetryModeBase: Int = 1,
        telemetryModeLocation: Int = 2,
        telemetryModeEnvironment: Int = 3,
        multiAcks: Int = 2,
    ) = SelfInfo(
        advertisementType = 1, txPower = 22, maxTxPower = 30,
        publicKey = ByteArray(32) { 0xAB.toByte() },
        latitude = 47.6062, longitude = -122.3321,
        multiAcks = multiAcks, advertisementLocationPolicy = advertisementLocationPolicy,
        telemetryModeEnvironment = telemetryModeEnvironment,
        telemetryModeLocation = telemetryModeLocation,
        telemetryModeBase = telemetryModeBase,
        manualAddContacts = manualAddContacts,
        radioFrequency = 910.525, radioBandwidth = 62.5,
        radioSpreadingFactor = 7, radioCodingRate = 5, name = name,
    )

    private fun capabilities(maxChannels: Int = 4, maxContacts: Int = 100) = DeviceCapabilities(
        firmwareVersion = 1, maxContacts = maxContacts, maxChannels = maxChannels,
        blePin = 0, firmwareBuild = "b", model = "m", version = "v", clientRepeat = false, pathHashMode = 0,
    )

    private fun sampleContact() = MeshContact(
        id = "01".repeat(32), publicKey = ByteArray(32) { 0x01 }, typeRawValue = 1, flags = 0,
        outPathLength = 3, outPath = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
        advertisedName = "RemoteNode", lastAdvertisement = 1_700_000_000L,
        latitude = 47.43, longitude = -120.36, lastModified = 1_700_000_100L,
    )

    private class OtherParamsCall(
        val autoAddContacts: Boolean,
        val telemetryModes: TelemetryModes,
        val advertLocationPolicyRaw: Int,
        val multiAcks: Int,
    )

    private class FakeSession(
        var selfInfo: SelfInfo,
        var capabilities: DeviceCapabilities,
        var privateKey: ByteArray? = ByteArray(64) { 0xAB.toByte() },
        var contacts: List<MeshContact> = emptyList(),
        val channels: MutableMap<Int, ChannelInfo> = mutableMapOf(),
    ) : NodeConfigSession {
        var importPrivateKeyCalled = false
        var otherParamsCall: OtherParamsCall? = null
        val setChannelCalls = mutableListOf<Pair<Uuid, ConfigImportPlan.ChannelWrite>>()
        val setContactCalls = mutableListOf<Pair<Uuid, MeshContact>>()

        override suspend fun getSelfInfo(): SelfInfo = selfInfo

        override suspend fun exportPrivateKey(): ByteArray =
            privateKey ?: throw MeshBridgeException(BridgeErrorCode.FEATURE_DISABLED, "export disabled")

        override suspend fun queryDevice(): DeviceCapabilities = capabilities

        override suspend fun getContacts(since: Long?): List<MeshContact> = contacts

        override suspend fun getChannel(index: Int): ChannelInfo =
            channels[index] ?: ChannelInfo(index, "", ByteArray(16))

        override suspend fun importPrivateKey(privateKey: ByteArray) {
            importPrivateKeyCalled = true
        }

        override suspend fun setNodeName(name: String) = Unit

        override suspend fun setLocation(latitude: Double, longitude: Double) = Unit

        override suspend fun setOtherParams(
            autoAddContacts: Boolean,
            telemetryModes: TelemetryModes,
            advertLocationPolicyRaw: Int,
            multiAcks: Int,
        ) {
            otherParamsCall = OtherParamsCall(autoAddContacts, telemetryModes, advertLocationPolicyRaw, multiAcks)
        }

        override suspend fun setRadioParams(
            frequencyKHz: UInt,
            bandwidthKHz: UInt,
            spreadingFactor: UByte,
            codingRate: UByte,
        ) = Unit

        override suspend fun setTxPower(txPower: Byte) = Unit

        override suspend fun setChannel(radioID: Uuid, index: Int, name: String, secret: ByteArray) {
            setChannelCalls += radioID to ConfigImportPlan.ChannelWrite(index, name, secret)
        }

        override suspend fun setContact(radioID: Uuid, contact: MeshContact) {
            setContactCalls += radioID to contact
        }
    }

    // MARK: - Export

    @Test
    fun `exportConfig round-trips through NodeConfigJson`() = runTest {
        val secret = ByteArray(16) { 0xAB.toByte() }
        val fake = FakeSession(
            selfInfo = selfInfo(),
            capabilities = capabilities(),
            contacts = listOf(sampleContact()),
            channels = mutableMapOf(1 to ChannelInfo(1, "Alpha", secret)),
        )
        val config = NodeConfigService(fake).exportConfig(ConfigSections().selectAll())

        assertEquals("TestNode", config.name)
        assertEquals("ab".repeat(32), config.publicKey)
        assertEquals("ab".repeat(64), config.privateKey)
        assertEquals(1, config.channels?.size)
        assertEquals("Alpha", config.channels?.first()?.name)
        assertEquals("ab".repeat(16), config.channels?.first()?.secret)
        assertEquals(1, config.contacts?.size)
        assertEquals("aabbcc", config.contacts?.first()?.outPath)

        val decoded = NodeConfigJson.decode(NodeConfigJson.encode(config))
        assertEquals(config, decoded)
    }

    @Test
    fun `exportConfig emits a keyless identity when firmware disables private-key export`() = runTest {
        val fake = FakeSession(selfInfo = selfInfo(), capabilities = capabilities(), privateKey = null)
        val config = NodeConfigService(fake).exportConfig(ConfigSections(nodeIdentity = true))

        assertEquals("TestNode", config.name)
        assertEquals("ab".repeat(32), config.publicKey)
        assertNull(config.privateKey)
    }

    // MARK: - Import

    private fun identityChannelContactConfig() = MeshCoreNodeConfig(
        name = "Node",
        privateKey = "ab".repeat(64),
        channels = listOf(MeshCoreNodeConfig.ChannelConfig("Ch1", "00112233445566778899aabbccddeeff")),
        contacts = listOf(
            MeshCoreNodeConfig.ContactConfig(
                type = 1u,
                name = "C1",
                publicKey = "ab".repeat(32),
                flags = 0u,
                latitude = "0",
                longitude = "0",
                lastAdvert = 0u,
                lastModified = 0u,
            ),
        ),
    )

    @Test
    fun `importConfig applies planned writes keyed by the connected radioID`() = runTest {
        val fake = FakeSession(selfInfo = selfInfo(), capabilities = capabilities())
        val sections = ConfigSections(nodeIdentity = true, channels = true, contacts = true)

        NodeConfigService(fake).importConfig(identityChannelContactConfig(), sections, Uuid("RADIO"))

        assertTrue(fake.importPrivateKeyCalled)
        assertEquals(1, fake.setChannelCalls.size)
        assertEquals(Uuid("RADIO"), fake.setChannelCalls.first().first)
        assertEquals("Ch1", fake.setChannelCalls.first().second.name)
        assertEquals(1, fake.setContactCalls.size)
        assertEquals(Uuid("RADIO"), fake.setContactCalls.first().first)
        assertEquals("C1", fake.setContactCalls.first().second.advertisedName)
    }

    @Test
    fun `importConfig reassigns the radioID from the post-identity callback`() = runTest {
        val fake = FakeSession(selfInfo = selfInfo(), capabilities = capabilities())
        val service = NodeConfigService(fake)
        service.onPostIdentityImport = { Uuid("RECONCILED") }
        val sections = ConfigSections(nodeIdentity = true, channels = true, contacts = true)

        service.importConfig(identityChannelContactConfig(), sections, Uuid("RADIO"))

        assertEquals(Uuid("RECONCILED"), fake.setChannelCalls.first().first)
        assertEquals(Uuid("RECONCILED"), fake.setContactCalls.first().first)
    }

    @Test
    fun `previewImport flags an overwrite of a device-configured channel slot`() = runTest {
        val secret = ByteArray(16) { 0xAB.toByte() }
        val fake = FakeSession(
            selfInfo = selfInfo(),
            capabilities = capabilities(),
            channels = mutableMapOf(1 to ChannelInfo(1, "Alpha", secret)),
        )
        // Same secret, different name → single-homes onto slot 1 and overwrites it.
        val config = MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("Beta", "ab".repeat(16))))

        val preview = NodeConfigService(fake).previewImport(config, ConfigSections(channels = true))
        assertTrue(preview.channelsOverwriteExisting)
    }

    // MARK: - ZephCore V-contact

    /** The virtual contact ZephCore streams in GET_CONTACTS, derived from this fixture's own public key. */
    private fun vContactKey(): ByteArray = VContactIdentity.publicKey(selfInfo().publicKey)!!

    private fun deviceContact(publicKey: ByteArray, name: String) = MeshContact(
        id = publicKey.toLowercaseHex(), publicKey = publicKey, typeRawValue = 1, flags = 0,
        outPathLength = 0xFF, outPath = ByteArray(0), advertisedName = name,
        lastAdvertisement = 0L, latitude = 0.0, longitude = 0.0, lastModified = 1_700_000_100L,
    )

    private fun contactConfig(publicKeyHex: String, name: String, lastModified: UInt = 0u) =
        MeshCoreNodeConfig.ContactConfig(
            type = 1u,
            name = name,
            publicKey = publicKeyHex,
            flags = 0u,
            latitude = "0",
            longitude = "0",
            lastAdvert = 0u,
            lastModified = lastModified,
        )

    @Test
    fun `the V-contact occupies no slot in the capacity check`() = runTest {
        val fake = FakeSession(
            selfInfo = selfInfo(),
            capabilities = capabilities(maxContacts = 2),
            contacts = listOf(deviceContact(vContactKey(), "vcontact")),
        )
        val config = MeshCoreNodeConfig(
            contacts = listOf(contactConfig("01".repeat(32), "A"), contactConfig("02".repeat(32), "B")),
        )

        NodeConfigService(fake).importConfig(config, ConfigSections(contacts = true), Uuid("RADIO"))

        assertEquals(listOf("A", "B"), fake.setContactCalls.map { it.second.advertisedName })
    }

    @Test
    fun `an exported V-contact is never written back as a real contact`() = runTest {
        val fake = FakeSession(
            selfInfo = selfInfo(),
            capabilities = capabilities(),
            contacts = listOf(deviceContact(vContactKey(), "vcontact")),
        )
        // Uppercase, and with a later last_modified than the device row, so only the key match can drop it.
        val config = MeshCoreNodeConfig(
            contacts = listOf(
                contactConfig(vContactKey().toLowercaseHex().uppercase(), "vcontact", lastModified = 42u),
                contactConfig("01".repeat(32), "A"),
            ),
        )

        NodeConfigService(fake).importConfig(config, ConfigSections(contacts = true), Uuid("RADIO"))

        assertEquals(listOf("A"), fake.setContactCalls.map { it.second.advertisedName })
        assertTrue(fake.setContactCalls.none { it.second.publicKey.contentEquals(vContactKey()) })
    }

    // MARK: - importOtherParams

    @Test
    fun `importOtherParams fills missing fields from the device and commits the merge`() = runTest {
        val fake = FakeSession(selfInfo = selfInfo(), capabilities = capabilities())
        NodeConfigService(fake).importOtherParams(
            MeshCoreNodeConfig.OtherSettings(manualAddContacts = 1u, advertLocationPolicy = 0u),
        )

        val call = fake.otherParamsCall!!
        assertEquals(false, call.autoAddContacts) // manualAdd 1 → auto-add off
        assertEquals(0, call.advertLocationPolicyRaw)
        assertEquals(TelemetryModes(base = 1, location = 2, environment = 3), call.telemetryModes)
        assertEquals(2, call.multiAcks)
    }

    @Test
    fun `importOtherParams whose merge matches the device sends no commit`() = runTest {
        val fake = FakeSession(selfInfo = selfInfo(), capabilities = capabilities())
        NodeConfigService(fake).importOtherParams(MeshCoreNodeConfig.OtherSettings())
        assertNull(fake.otherParamsCall)
    }

    @Test
    fun `importOtherParams forwards an unmodeled advert-location-policy byte verbatim`() = runTest {
        val unmodeledPolicy = 99
        val fake = FakeSession(selfInfo = selfInfo(), capabilities = capabilities())
        NodeConfigService(fake).importOtherParams(
            MeshCoreNodeConfig.OtherSettings(manualAddContacts = 0u, advertLocationPolicy = unmodeledPolicy.toUByte()),
        )
        assertEquals(unmodeledPolicy, fake.otherParamsCall!!.advertLocationPolicyRaw)
    }
}
