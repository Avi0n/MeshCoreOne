@file:Suppress("LongParameterList")

package one.meshcore.shared.settings

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure export builders, step counting, post-identity resolution, pref/radio diff gates, and the
 * [executeConfigImport] orchestration seam, mirroring the behaviors the Swift `NodeConfigServiceTests` cover.
 * Exercised through a spy [ConfigImportWriters] so ordering, progress, and failure isolation are asserted
 * without a live session.
 */
class NodeConfigExecuteTest {
    // MARK: - Fixtures

    private fun testSelfInfo(
        manualAddContacts: Boolean = false,
        radioFrequency: Double = 910.525,
        radioBandwidth: Double = 62.5,
        txPower: Int = 22,
    ) = SelfInfo(
        advertisementType = 1,
        txPower = txPower,
        maxTxPower = 30,
        publicKey = ByteArray(32) { 0xAB.toByte() },
        latitude = 47.6062,
        longitude = -122.3321,
        multiAcks = 2,
        advertisementLocationPolicy = 1,
        telemetryModeEnvironment = 3,
        telemetryModeLocation = 2,
        telemetryModeBase = 1,
        manualAddContacts = manualAddContacts,
        radioFrequency = radioFrequency,
        radioBandwidth = radioBandwidth,
        radioSpreadingFactor = 7,
        radioCodingRate = 5,
        name = "TestNode",
    )

    private fun contact(
        typeRawValue: Int = 1,
        flags: Int = 0x02,
        outPathLength: Int = 3,
        outPath: ByteArray = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
        latitude: Double = 47.43,
        longitude: Double = -120.36,
    ) = MeshContact(
        id = "01".repeat(32),
        publicKey = ByteArray(32) { 0x01 },
        typeRawValue = typeRawValue,
        flags = flags,
        outPathLength = outPathLength,
        outPath = outPath,
        advertisedName = "RemoteNode",
        lastAdvertisement = 1_700_000_000L,
        latitude = latitude,
        longitude = longitude,
        lastModified = 1_700_000_100L,
    )

    // MARK: - buildRadioSettings

    @Test
    fun `buildRadioSettings converts MHz frequency to kHz and kHz bandwidth to Hz`() {
        val radio = buildRadioSettings(testSelfInfo())
        assertEquals(910_525u, radio.frequency)
        assertEquals(62_500u, radio.bandwidth)
    }

    @Test
    fun `buildRadioSettings copies spreading factor, coding rate, and tx power`() {
        val radio = buildRadioSettings(testSelfInfo())
        assertEquals(7u.toUByte(), radio.spreadingFactor)
        assertEquals(5u.toUByte(), radio.codingRate)
        assertEquals(22.toByte(), radio.txPower)
    }

    @Test
    fun `buildRadioSettings rounds frequency to the nearest kHz`() {
        // Truncation would yield 512001; rounding restores the representable 512002.
        val radio = buildRadioSettings(testSelfInfo(radioFrequency = 512.002))
        assertEquals(512_002u, radio.frequency)
    }

    // MARK: - buildOtherSettings

    @Test
    fun `buildOtherSettings maps manualAddContacts and exports only two companion fields`() {
        val other = buildOtherSettings(testSelfInfo(manualAddContacts = false))
        assertEquals(0u.toUByte(), other.manualAddContacts)
        assertEquals(1u.toUByte(), other.advertLocationPolicy)
        assertEquals(null, other.telemetryModeBase)
        assertEquals(null, other.telemetryModeLocation)
        assertEquals(null, other.telemetryModeEnvironment)
        assertEquals(null, other.multiAcks)
        assertEquals(null, other.advertisementType)

        assertEquals(1u.toUByte(), buildOtherSettings(testSelfInfo(manualAddContacts = true)).manualAddContacts)
    }

    // MARK: - buildContactConfig

    @Test
    fun `buildContactConfig populates fields and hex outPath for routed contacts`() {
        val config = buildContactConfig(contact())
        assertEquals(1u.toUByte(), config.type)
        assertEquals("RemoteNode", config.name)
        assertEquals("01".repeat(32), config.publicKey)
        assertEquals(0x02u.toUByte(), config.flags)
        assertEquals("47.43", config.latitude)
        assertEquals("-120.36", config.longitude)
        assertEquals(1_700_000_000u, config.lastAdvert)
        assertEquals(1_700_000_100u, config.lastModified)
        assertEquals("aabbcc", config.outPath)
        assertEquals(0u.toUByte(), config.pathHashMode)
    }

    @Test
    fun `buildContactConfig uses null outPath and null hash mode for flood routing`() {
        val config = buildContactConfig(contact(outPathLength = 0xFF, outPath = ByteArray(0)))
        assertEquals(null, config.outPath)
        assertEquals(null, config.pathHashMode)
    }

    @Test
    fun `buildContactConfig uses empty string outPath for direct zero-length path`() {
        val config = buildContactConfig(contact(outPathLength = 0, outPath = ByteArray(0)))
        assertEquals("", config.outPath)
    }

    @Test
    fun `buildContactConfig truncates outPath to the meaningful path bytes`() {
        val config = buildContactConfig(
            contact(
                outPathLength = 2,
                outPath = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            ),
        )
        assertEquals("aabb", config.outPath)
    }

    @Test
    fun `buildContactConfig exports pathHashMode for a mode-1 two-byte contact`() {
        val encoded = PathEncoding.encodePathLen(hashSize = 2, hopCount = 3) // 0x43
        val config = buildContactConfig(
            contact(
                outPathLength = encoded,
                outPath = byteArrayOf(
                    0xAA.toByte(),
                    0xBB.toByte(),
                    0xCC.toByte(),
                    0xDD.toByte(),
                    0xEE.toByte(),
                    0xFF.toByte(),
                ),
            ),
        )
        assertEquals(1u.toUByte(), config.pathHashMode)
        assertEquals("aabbccddeeff", config.outPath)
    }

    // MARK: - Step counting

    private fun emptySlots(count: Int) = (0 until count).map { DeviceChannelSlot(it, "", ByteArray(0), false) }

    private fun fullSectionsPlan() = planConfigImport(
        config = MeshCoreNodeConfig(
            name = "Test",
            privateKey = "ab".repeat(64),
            radioSettings = MeshCoreNodeConfig.RadioSettings(910_525u, 62_500u, 7u, 5u, 20),
            positionSettings = MeshCoreNodeConfig.PositionSettings("47.0", "-122.0"),
            otherSettings = MeshCoreNodeConfig.OtherSettings(manualAddContacts = 0u),
            channels = listOf(
                MeshCoreNodeConfig.ChannelConfig("Ch1", "00112233445566778899aabbccddeeff"),
                MeshCoreNodeConfig.ChannelConfig("Ch2", "ffeeddccbbaa99887766554433221100"),
            ),
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
        ),
        sections = ConfigSections().selectAll(),
        device = ConfigImportDevice(
            maxChannels = 8,
            maxContacts = 100,
            maxTxPower = 30,
            existingChannels = emptySlots(8),
            existingContacts = emptyMap(),
        ),
    )

    @Test
    fun `stepCount sums one step per resolved write plus two for radio`() {
        // privateKey + name + position + other + 2 channels + 1 contact + radio(2) = 9
        assertEquals(9, stepCount(fullSectionsPlan()))
    }

    @Test
    fun `Two same-secret channels still count as two write steps`() {
        val plan = planConfigImport(
            config = MeshCoreNodeConfig(
                channels = listOf(
                    MeshCoreNodeConfig.ChannelConfig("Alpha", "00112233445566778899aabbccddeeff"),
                    MeshCoreNodeConfig.ChannelConfig("Beta", "00112233445566778899aabbccddeeff"),
                ),
            ),
            sections = ConfigSections(channels = true),
            device = ConfigImportDevice(
                maxChannels = 8,
                maxContacts = 100,
                maxTxPower = 30,
                existingChannels = emptySlots(8),
                existingContacts = emptyMap(),
            ),
        )
        assertEquals(2, plan.channelWrites.size)
        assertEquals(2, stepCount(plan))
    }

    @Test
    fun `stepCount is zero for an empty plan`() {
        val plan = planConfigImport(
            config = MeshCoreNodeConfig(),
            sections = ConfigSections(),
            device = ConfigImportDevice(
                maxChannels = 8,
                maxContacts = 100,
                maxTxPower = 30,
                existingChannels = emptyList(),
                existingContacts = emptyMap(),
            ),
        )
        assertEquals(0, stepCount(plan))
    }

    // MARK: - Post-identity resolution

    @Test
    fun `resolveEffectiveRadioID returns callback result when private key was imported`() = runTest {
        val reconciled = Uuid("RECONCILED")
        assertEquals(reconciled, resolveEffectiveRadioID(Uuid("ORIGINAL"), didImportPrivateKey = true) { reconciled })
    }

    @Test
    fun `resolveEffectiveRadioID skips callback when no private key was imported`() = runTest {
        var calls = 0
        val result = resolveEffectiveRadioID(Uuid("ORIGINAL"), didImportPrivateKey = false) {
            calls += 1
            Uuid("OTHER")
        }
        assertEquals(Uuid("ORIGINAL"), result)
        assertEquals(0, calls)
    }

    @Test
    fun `resolveEffectiveRadioID returns original when callback returns null`() = runTest {
        assertEquals(Uuid("ORIGINAL"), resolveEffectiveRadioID(Uuid("ORIGINAL"), didImportPrivateKey = true) { null })
    }

    @Test
    fun `resolveEffectiveRadioID returns original for a null callback`() = runTest {
        assertEquals(
            Uuid("ORIGINAL"),
            resolveEffectiveRadioID(Uuid("ORIGINAL"), didImportPrivateKey = true, callback = null),
        )
    }

    // MARK: - Diff gates

    private fun matchingSelfInfo(
        name: String = "Test",
        latitude: Double = 47.0,
        longitude: Double = -122.0,
        radioFrequencyMHz: Double = 910.525,
        radioBandwidthKHz: Double = 62.5,
        spreadingFactor: Int = 7,
        codingRate: Int = 5,
        txPower: Int = 20,
    ) = SelfInfo(
        advertisementType = 0, txPower = txPower, maxTxPower = 30,
        publicKey = ByteArray(32), latitude = latitude, longitude = longitude,
        multiAcks = 0, advertisementLocationPolicy = 0,
        telemetryModeEnvironment = 0, telemetryModeLocation = 0, telemetryModeBase = 0,
        manualAddContacts = false,
        radioFrequency = radioFrequencyMHz, radioBandwidth = radioBandwidthKHz,
        radioSpreadingFactor = spreadingFactor, radioCodingRate = codingRate, name = name,
    )

    @Test
    fun `Each pref gate fires only when its field differs from the device`() {
        val info = matchingSelfInfo()
        val radio = MeshCoreNodeConfig.RadioSettings(910_525u, 62_500u, 7u, 5u, 20)

        assertFalse(nodeNameNeedsWrite("Test", info))
        assertTrue(nodeNameNeedsWrite("Renamed", info))

        // setNodeName truncates to the firmware field width, so a name differing only past that width stores
        // identically and must not re-commit.
        val storedName = "a".repeat(NodeConfigLimits.MAX_USABLE_NAME_BYTES)
        assertFalse(nodeNameNeedsWrite(storedName + "EXTRA", matchingSelfInfo(name = storedName)))

        assertFalse(locationNeedsWrite(ConfigImportPlan.Coordinate(47.0, -122.0), info))
        assertTrue(locationNeedsWrite(ConfigImportPlan.Coordinate(47.5, -122.0), info))

        assertFalse(radioParamsNeedWrite(radio, info))
        assertTrue(radioParamsNeedWrite(radio.copy(spreadingFactor = 8u), info))

        assertFalse(txPowerNeedsWrite(radio, info))
        assertTrue(txPowerNeedsWrite(radio.copy(txPower = 19), info))
    }

    // MARK: - executeConfigImport orchestration

    private val executeSections = ConfigSections(nodeIdentity = true, channels = true, contacts = true)
    private val radioExecuteSections = ConfigSections(nodeIdentity = true, radioSettings = true, contacts = true)

    private fun executePlan() = planConfigImport(
        config = MeshCoreNodeConfig(
            name = "Node",
            privateKey = "ab".repeat(64),
            channels = listOf(
                MeshCoreNodeConfig.ChannelConfig("Ch1", "00112233445566778899aabbccddeeff"),
                MeshCoreNodeConfig.ChannelConfig("Ch2", "ffeeddccbbaa99887766554433221100"),
            ),
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
        ),
        sections = executeSections,
        device = ConfigImportDevice(
            maxChannels = 8,
            maxContacts = 100,
            maxTxPower = 30,
            existingChannels = emptySlots(8),
            existingContacts = emptyMap(),
        ),
    )

    private fun radioExecutePlan() = planConfigImport(
        config = MeshCoreNodeConfig(
            name = "Node",
            privateKey = "ab".repeat(64),
            radioSettings = MeshCoreNodeConfig.RadioSettings(910_525u, 62_500u, 7u, 5u, 20),
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
        ),
        sections = radioExecuteSections,
        device = ConfigImportDevice(
            maxChannels = 8,
            maxContacts = 100,
            maxTxPower = 30,
            existingChannels = emptySlots(8),
            existingContacts = emptyMap(),
        ),
    )

    private class ExecuteSpy {
        val calls = mutableListOf<String>()
        val progress = mutableListOf<ImportStep>()
    }

    private fun nonMatchingSelfInfo() = SelfInfo(
        advertisementType = 0, txPower = 0, maxTxPower = 30,
        publicKey = ByteArray(32), latitude = 0.0, longitude = 0.0,
        multiAcks = 0, advertisementLocationPolicy = 0,
        telemetryModeEnvironment = 0, telemetryModeLocation = 0, telemetryModeBase = 0,
        manualAddContacts = false,
        radioFrequency = 1.0, radioBandwidth = 1.0, radioSpreadingFactor = 1, radioCodingRate = 1,
        name = " unset",
    )

    private fun spyWriters(
        spy: ExecuteSpy,
        selfInfo: () -> SelfInfo = { nonMatchingSelfInfo() },
        throwOn: (String) -> Boolean = { false },
        setContact: (suspend (Uuid, PlannedContact) -> Unit)? = null,
    ): ConfigImportWriters {
        suspend fun step(label: String) {
            spy.calls += label
            if (throwOn(label)) throw NodeConfigError.InvalidRadioSettings(RadioField.FREQUENCY)
        }
        return ConfigImportWriters(
            getSelfInfo = {
                spy.calls += "getSelfInfo"
                selfInfo()
            },
            importPrivateKey = { step("importPrivateKey") },
            setNodeName = { step("setNodeName") },
            setLocation = { _, _ -> step("setLocation") },
            setOtherParams = { step("setOtherParams") },
            resolveEffectiveRadioID = { original, _ ->
                spy.calls += "resolveEffectiveRadioID"
                original
            },
            setRadioParams = { step("setRadioParams") },
            setTxPower = { step("setTxPower") },
            setChannel = { _, write -> step("setChannel:${write.name}") },
            setContact = setContact ?: { _, planned -> step("addContact:${planned.advertisedName}") },
        )
    }

    @Test
    fun `Execute applies identity before channels and contacts and reports one step per write`() = runTest {
        val spy = ExecuteSpy()
        executeConfigImport(
            executePlan(),
            executeSections,
            Uuid("RADIO"),
            spyWriters(spy),
            hooks = ConfigImportHooks(onProgress = { spy.progress += it.step }),
        )

        val identityIndex = spy.calls.indexOf("importPrivateKey")
        val nameIndex = spy.calls.indexOf("setNodeName")
        val channelIndex = spy.calls.indexOfFirst { it.startsWith("setChannel") }
        val contactIndex = spy.calls.indexOfFirst { it.startsWith("addContact") }
        assertTrue(identityIndex in 0 until channelIndex)
        assertTrue(nameIndex in 0 until channelIndex)
        assertTrue(identityIndex in 0 until contactIndex)
        // privateKey + name + 2 channels + 1 contact = 5 writes, each reporting once.
        assertEquals(5, spy.progress.size)
    }

    @Test
    fun `A first-write failure reports no progress so the import reads as clean`() = runTest {
        val spy = ExecuteSpy()
        assertFailsWith<NodeConfigError> {
            executeConfigImport(
                executePlan(),
                executeSections,
                Uuid("RADIO"),
                spyWriters(spy, throwOn = { it == "importPrivateKey" }),
                hooks = ConfigImportHooks(onProgress = { spy.progress += it.step }),
            )
        }
        assertTrue(spy.progress.isEmpty())
    }

    @Test
    fun `A mid-sequence failure reports progress only for the writes that already succeeded`() = runTest {
        val spy = ExecuteSpy()
        assertFailsWith<NodeConfigError> {
            executeConfigImport(
                executePlan(),
                executeSections,
                Uuid("RADIO"),
                spyWriters(spy, throwOn = { it.startsWith("setChannel") }),
                hooks = ConfigImportHooks(onProgress = { spy.progress += it.step }),
            )
        }
        assertEquals(listOf(ImportStep.PrivateKey, ImportStep.NodeName), spy.progress)
    }

    @Test
    fun `A local-save failure after the device add still reports the contact as applied`() = runTest {
        val spy = ExecuteSpy()
        // Models the post-device-add local save failing: the writer records but does not throw, so execute
        // must complete and report the contact's progress.
        val writers = spyWriters(
            spy,
            setContact = { _, planned -> spy.calls += "addContact:${planned.advertisedName}" },
        )
        executeConfigImport(
            executePlan(),
            executeSections,
            Uuid("RADIO"),
            writers,
            hooks = ConfigImportHooks(onProgress = { spy.progress += it.step }),
        )
        assertTrue(spy.progress.contains(ImportStep.Contact("C1")))
    }

    @Test
    fun `Execute writes radio after contacts and tx power after radio params`() = runTest {
        val spy = ExecuteSpy()
        executeConfigImport(
            radioExecutePlan(),
            radioExecuteSections,
            Uuid("RADIO"),
            spyWriters(spy),
            hooks = ConfigImportHooks(onProgress = { spy.progress += it.step }),
        )
        val contactIndex = spy.calls.indexOfLast { it.startsWith("addContact") }
        val radioIndex = spy.calls.indexOf("setRadioParams")
        val txPowerIndex = spy.calls.indexOf("setTxPower")
        assertTrue(contactIndex < radioIndex)
        assertTrue(radioIndex < txPowerIndex)
        assertEquals(
            listOf(
                ImportStep.PrivateKey,
                ImportStep.NodeName,
                ImportStep.Contact("C1"),
                ImportStep.RadioParameters,
                ImportStep.TxPower,
            ),
            spy.progress,
        )
    }

    @Test
    fun `A tx-power failure after radio params rethrows and reports radio progress but not tx power`() = runTest {
        val spy = ExecuteSpy()
        assertFailsWith<NodeConfigError> {
            executeConfigImport(
                radioExecutePlan(),
                radioExecuteSections,
                Uuid("RADIO"),
                spyWriters(spy, throwOn = { it == "setTxPower" }),
                hooks = ConfigImportHooks(onProgress = { spy.progress += it.step }),
            )
        }
        assertTrue(spy.calls.contains("setRadioParams"))
        assertTrue(spy.calls.contains("setTxPower"))
        assertTrue(spy.progress.contains(ImportStep.RadioParameters))
        assertFalse(spy.progress.contains(ImportStep.TxPower))
    }

    @Test
    fun `Execute over a full plan emits exactly stepCount progress steps across all branches`() = runTest {
        val plan = fullSectionsPlan()
        val spy = ExecuteSpy()
        executeConfigImport(
            plan,
            ConfigSections().selectAll(),
            Uuid("RADIO"),
            spyWriters(spy),
            hooks = ConfigImportHooks(onProgress = { spy.progress += it.step }),
        )
        assertEquals(stepCount(plan), spy.progress.size)
        val kinds = spy.progress.map {
            when (it) {
                is ImportStep.Position -> "position"
                is ImportStep.OtherParameters -> "other"
                is ImportStep.PrivateKey -> "privateKey"
                is ImportStep.NodeName -> "nodeName"
                is ImportStep.RadioParameters -> "radio"
                is ImportStep.TxPower -> "txPower"
                is ImportStep.Channel -> "channel"
                is ImportStep.Contact -> "contact"
            }
        }.toSet()
        assertEquals(
            setOf("position", "other", "privateKey", "nodeName", "radio", "txPower", "channel", "contact"),
            kinds,
        )
    }

    @Test
    fun `An unchanged config skips every pref and radio device write but still reports progress`() = runTest {
        val plan = fullSectionsPlan()
        val spy = ExecuteSpy()
        executeConfigImport(
            plan,
            ConfigSections().selectAll(),
            Uuid("RADIO"),
            spyWriters(spy, selfInfo = { matchingSelfInfo() }),
            hooks = ConfigImportHooks(onProgress = { spy.progress += it.step }),
        )
        assertFalse(spy.calls.contains("setNodeName"))
        assertFalse(spy.calls.contains("setLocation"))
        assertFalse(spy.calls.contains("setRadioParams"))
        assertFalse(spy.calls.contains("setTxPower"))
        assertEquals(stepCount(plan), spy.progress.size)
        assertTrue(spy.progress.contains(ImportStep.NodeName))
        assertTrue(spy.progress.contains(ImportStep.RadioParameters))
        assertTrue(spy.progress.contains(ImportStep.TxPower))
    }

    @Test
    fun `A tx-power-only change writes only setTxPower among the radio writes`() = runTest {
        val spy = ExecuteSpy()
        val info = matchingSelfInfo(name = "Node", txPower = 15)
        executeConfigImport(
            radioExecutePlan(),
            radioExecuteSections,
            Uuid("RADIO"),
            spyWriters(spy, selfInfo = { info }),
            hooks = ConfigImportHooks(onProgress = { spy.progress += it.step }),
        )
        assertFalse(spy.calls.contains("setNodeName"))
        assertFalse(spy.calls.contains("setRadioParams"))
        assertTrue(spy.calls.contains("setTxPower"))
    }
}
