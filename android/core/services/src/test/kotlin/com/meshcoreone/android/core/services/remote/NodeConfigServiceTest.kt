// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/NodeConfigServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.ChannelConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.ContactConfig
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.model.encodePathLen
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

class NodeConfigServiceTest {
    private val testSelfInfo = nodeConfigSelfInfo(
        advertisementType = 1u, txPower = 22, maxTxPower = 30, publicKey = nodeConfigBytes(0xAB, 32),
        latitude = 47.6062, longitude = -122.3321, multiAcks = 2u, advertisementLocationPolicy = 1u,
        telemetryModeEnvironment = 3u, telemetryModeLocation = 2u, telemetryModeBase = 1u, manualAddContacts = false,
        radioFrequency = 910.525, radioBandwidth = 62.5, radioSpreadingFactor = 7u, radioCodingRate = 5u, name = "TestNode",
    )
    private val testContact = nodeConfigMeshContact(
        publicKey = nodeConfigBytes(0x01, 32), type = ContactType.CHAT, flags = 0x02u, outPathLength = 3u,
        outPath = Bytes.of(0xAA, 0xBB, 0xCC), advertisedName = "RemoteNode",
        lastAdvertisement = Instant.ofEpochSecond(1_700_000_000), latitude = 47.43, longitude = -120.36,
        lastModified = Instant.ofEpochSecond(1_700_000_100),
    )
    private val floodContact = nodeConfigMeshContact(
        publicKey = nodeConfigBytes(0x02, 32), type = ContactType.REPEATER, outPathLength = 0xFFu, advertisedName = "FloodNode",
        lastAdvertisement = Instant.ofEpochSecond(1_700_001_000), lastModified = Instant.ofEpochSecond(1_700_001_100),
    )
    private val zeroPathContact = nodeConfigMeshContact(
        publicKey = nodeConfigBytes(0x03, 32), outPathLength = 0u, advertisedName = "DirectNode",
        lastAdvertisement = Instant.ofEpochSecond(1_700_002_000), lastModified = Instant.ofEpochSecond(1_700_002_100),
    )

    private val executeSections = ConfigSections(nodeIdentity = true, channels = true, contacts = true)
    private val radioExecuteSections = ConfigSections(nodeIdentity = true, radioSettings = true, contacts = true)
    private val c1 = ContactConfig(
        type = 1u, name = "C1", publicKey = "ab".repeat(32), flags = 0u, latitude = "0", longitude = "0", lastAdvert = 0u, lastModified = 0u,
    )
    private val twoChannels = SnapshotList.of(
        ChannelConfig("Ch1", "00112233445566778899aabbccddeeff"), ChannelConfig("Ch2", "ffeeddccbbaa99887766554433221100"),
    )
    private val radio20 = MeshCoreNodeConfig.RadioSettings(910_525u, 62_500u, 7u, 5u, 20)

    private fun executePlan() = nodeConfigPlan(
        MeshCoreNodeConfig(name = "Node", privateKey = "ab".repeat(64), channels = twoChannels, contacts = SnapshotList.of(c1)),
        executeSections,
    )

    private fun radioExecutePlan() = nodeConfigPlan(
        MeshCoreNodeConfig(name = "Node", privateKey = "ab".repeat(64), radioSettings = radio20, contacts = SnapshotList.of(c1)),
        radioExecuteSections,
    )

    private fun fullSectionsPlan() = nodeConfigPlan(
        MeshCoreNodeConfig(
            name = "Test", privateKey = "ab".repeat(64), radioSettings = radio20,
            positionSettings = MeshCoreNodeConfig.PositionSettings("47.0", "-122.0"),
            otherSettings = MeshCoreNodeConfig.OtherSettings(manualAddContacts = 0u),
            channels = twoChannels, contacts = SnapshotList.of(c1),
        ),
        nodeConfigAllSections,
    )

    private fun matchingSelfInfo(name: String = "Test", txPower: Byte = 20) = nodeConfigSelfInfo(
        txPower = txPower, latitude = 47.0, longitude = -122.0, radioFrequency = 910.525, radioBandwidth = 62.5,
        radioSpreadingFactor = 7u, radioCodingRate = 5u, name = name,
    )

    private suspend fun execute(
        plan: ConfigImportPlan, sections: ConfigSections, spy: NodeConfigExecuteSpy, writers: ConfigImportWriters = spy.writers(),
    ) = executeConfigImport(plan, sections, RadioId(UUID.randomUUID()), writers, NodeConfigLogger.NONE, { spy.recordProgress(it.step) })

    @TestFactory
    fun sourceCases() = nodeConfigSourceCases(
        "NodeConfigServiceTests",
        "buildRadioSettings converts MHz frequency to kHz" to {
            assertEquals(910_525u, NodeConfigService.buildRadioSettings(testSelfInfo).frequency)
        },
        "buildRadioSettings converts kHz bandwidth to Hz" to {
            assertEquals(62_500u, NodeConfigService.buildRadioSettings(testSelfInfo).bandwidth)
        },
        "buildRadioSettings copies spreading factor, coding rate, and tx power" to {
            val radio = NodeConfigService.buildRadioSettings(testSelfInfo)
            assertEquals(7.toUByte(), radio.spreadingFactor); assertEquals(5.toUByte(), radio.codingRate); assertEquals(22.toByte(), radio.txPower)
        },
        "buildRadioSettings rounds frequency to the nearest kHz" to {
            val info = nodeConfigSelfInfo(txPower = 22, publicKey = nodeConfigBytes(0xAB, 32), radioFrequency = 512.002)
            // Truncation would yield 512001; rounding restores the representable 512002.
            assertEquals(512_002u, NodeConfigService.buildRadioSettings(info).frequency)
        },
        "buildOtherSettings maps manualAddContacts=false to 0" to {
            assertEquals(0.toUByte(), NodeConfigService.buildOtherSettings(testSelfInfo).manualAddContacts)
        },
        "buildOtherSettings maps manualAddContacts=true to 1" to {
            val info = nodeConfigSelfInfo(txPower = 10, manualAddContacts = true, name = "Test")
            assertEquals(1.toUByte(), NodeConfigService.buildOtherSettings(info).manualAddContacts)
        },
        "buildOtherSettings exports only 2 companion-app fields" to {
            val other = NodeConfigService.buildOtherSettings(testSelfInfo)
            assertEquals(MeshCoreNodeConfig.OtherSettings(manualAddContacts = 0u, advertLocationPolicy = 1u), other)
        },
        "buildContactConfig populates all fields from MeshContact" to {
            val config = NodeConfigService.buildContactConfig(testContact)
            assertEquals(1.toUByte(), config.type)
            assertEquals("RemoteNode", config.name)
            assertEquals(nodeConfigBytes(0x01, 32).hexString, config.publicKey)
            assertEquals(0x02.toUByte(), config.flags)
            assertEquals("47.43", config.latitude)
            assertEquals("-120.36", config.longitude)
            assertEquals(1_700_000_000u, config.lastAdvert)
            assertEquals(1_700_000_100u, config.lastModified)
        },
        "buildContactConfig includes hex outPath for routed contacts" to {
            assertEquals("aabbcc", NodeConfigService.buildContactConfig(testContact).outPath)
        },
        "buildContactConfig uses nil outPath for flood routing" to {
            assertNull(NodeConfigService.buildContactConfig(floodContact).outPath)
        },
        "buildContactConfig uses empty string outPath for direct (zero-length) path" to {
            assertEquals("", NodeConfigService.buildContactConfig(zeroPathContact).outPath)
        },
        "buildContactConfig truncates outPath to outPathLength bytes" to {
            val contact = nodeConfigMeshContact(
                id = "test", publicKey = nodeConfigBytes(0x04, 32), outPathLength = 2u, outPath = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD),
                advertisedName = "Truncated", lastAdvertisement = Instant.now(), lastModified = Instant.now(),
            )
            assertEquals("aabb", NodeConfigService.buildContactConfig(contact).outPath)
        },
        "stepCount sums one step per resolved write plus two for radio" to {
            // privateKey(1) + name(1) + position(1) + other(1) + channels(2) + contacts(1) + radio(2) = 9
            assertEquals(9, NodeConfigService.stepCount(fullSectionsPlan()))
        },
        "Two same-secret channels still count as two write steps" to {
            val plan = nodeConfigPlan(
                MeshCoreNodeConfig(channels = SnapshotList.of(
                    ChannelConfig("Alpha", "00112233445566778899aabbccddeeff"), ChannelConfig("Beta", "00112233445566778899aabbccddeeff"),
                )),
                ConfigSections(channels = true),
            )
            assertEquals(2, plan.channelWrites.size)
            assertEquals(2, NodeConfigService.stepCount(plan))
        },
        "stepCount is zero for an empty plan" to {
            assertEquals(0, NodeConfigService.stepCount(nodeConfigPlan(MeshCoreNodeConfig(), nodeConfigNoSections, existingChannels = emptyList())))
        },
        "Partial OtherSettings fills missing fields from current device values" to {
            val settings = NodeConfigFakeSettings(testSelfInfo)
            serviceWith(settings).importOtherParams(MeshCoreNodeConfig.OtherSettings(manualAddContacts = 1u, advertLocationPolicy = 0u))
            // Imported values take precedence; telemetry (base 1, location 2, environment 3 -> 0b111001) and
            // multiAcks 2 fall back to the device's current values.
            assertEquals("setOtherParams:false,57,0,2", settings.calls.last())
        },
        "Full OtherSettings uses all imported values" to {
            val settings = NodeConfigFakeSettings(testSelfInfo)
            serviceWith(settings).importOtherParams(MeshCoreNodeConfig.OtherSettings(0u, 2u, 3u, 1u, 2u, 5u, 4u))
            // base 3, location 1, environment 2 -> (2 << 4) | (1 << 2) | 3 = 39; advertisementType is not forwarded.
            assertEquals("setOtherParams:true,39,2,5", settings.calls.last())
        },
        "buildRadioSettings round-trips through config format" to {
            val radio = NodeConfigService.buildRadioSettings(testSelfInfo)
            assertEquals(910_525u, radio.frequency); assertEquals(62_500u, radio.bandwidth)
        },
        "buildContactConfig and import produce consistent outPath" to {
            assertEquals("aabbcc", NodeConfigService.buildContactConfig(testContact).outPath)
            assertEquals(3.toUByte(), testContact.outPathLength)
        },
        "buildContactConfig and import produce consistent flood path" to {
            assertNull(NodeConfigService.buildContactConfig(floodContact).outPath)
            assertEquals(0xFF.toUByte(), floodContact.outPathLength)
        },
        "Direct contact round-trips through export and import without becoming flood" to {
            val exported = NodeConfigService.buildContactConfig(zeroPathContact)
            assertEquals("", exported.outPath)
            val reimported = NodeConfigJson.decodeContact(NodeConfigJson.encodeContact(exported))
            assertEquals("", reimported.outPath)
            // The planner's three-way branch must keep "" as direct (0), not flood (0xFF).
            val plan = nodeConfigPlan(MeshCoreNodeConfig(contacts = SnapshotList.of(reimported)), ConfigSections(contacts = true))
            assertEquals(0.toUByte(), plan.contactRecords.single().outPathLength)
        },
        "buildContactConfig exports pathHashMode for mode 0 contact" to {
            assertEquals(0.toUByte(), NodeConfigService.buildContactConfig(testContact).pathHashMode)
        },
        "buildContactConfig exports pathHashMode for mode 1 (2-byte) contact" to {
            val contact = nodeConfigMeshContact(
                id = "mode1", publicKey = nodeConfigBytes(0x05, 32), outPathLength = encodePathLen(2, 3),
                outPath = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF), advertisedName = "Mode1Node",
            )
            val config = NodeConfigService.buildContactConfig(contact)
            assertEquals(1.toUByte(), config.pathHashMode)
            assertEquals("aabbccddeeff", config.outPath)
        },
        "buildContactConfig exports nil pathHashMode for flood contacts" to {
            assertNull(NodeConfigService.buildContactConfig(floodContact).pathHashMode)
        },
        "ContactConfig import with pathHashMode encodes outPathLength correctly" to {
            val config = NodeConfigJson.decodeContact(contactJSON("ab", "\"aabbccddeeff\"", ",\"path_hash_mode\": 1"))
            assertEquals(1.toUByte(), config.pathHashMode)
            // 6 bytes / 2 bytes per hop = 3 hops, mode 1 -> 0b01_000011 = 0x43
            val record = nodeConfigPlan(MeshCoreNodeConfig(contacts = SnapshotList.of(config)), ConfigSections(contacts = true)).contactRecords.single()
            assertEquals(0x43.toUByte(), record.outPathLength)
            assertEquals(0x43.toUByte(), encodePathLen(2, 3))
        },
        "ContactConfig import without pathHashMode defaults to mode 0" to {
            val config = NodeConfigJson.decodeContact(contactJSON("ab", "\"aabbcc\"", ""))
            assertNull(config.pathHashMode)
            val record = nodeConfigPlan(MeshCoreNodeConfig(contacts = SnapshotList.of(config)), ConfigSections(contacts = true)).contactRecords.single()
            assertEquals(3.toUByte(), record.outPathLength)
        },
        "Direct contact with pathHashMode imports as outPathLength 0, not mode-encoded" to {
            val config = NodeConfigJson.decodeContact(contactJSON("cd", "\"\"", ",\"path_hash_mode\": 1", name = "DirectMode1"))
            assertEquals(1.toUByte(), config.pathHashMode)
            assertEquals("", config.outPath)
            val record = nodeConfigPlan(MeshCoreNodeConfig(contacts = SnapshotList.of(config)), ConfigSections(contacts = true)).contactRecords.single()
            assertEquals(0.toUByte(), record.outPathLength, "Direct contact must encode as 0, not mode-encoded 0x40")
        },
        "NodeConfigServiceError has descriptive messages" to {
            assertTrue(assertNotNull(NodeConfigServiceError.InvalidChannelSecret(2, 30).message).contains("Channel 2"))
            assertTrue(assertNotNull(NodeConfigServiceError.InvalidContactPublicKey("BadContact").message).contains("BadContact"))
            val modeError = assertNotNull(NodeConfigServiceError.InvalidPathHashMode("BadNode", 5u).message)
            assertTrue(modeError.contains("BadNode")); assertTrue(modeError.contains("5"))
        },
        "ImportProgress stores step info" to {
            val progress = ImportProgress(ImportStep.Contact("Alice"), 3, 10)
            assertEquals(ImportStep.Contact("Alice"), progress.step); assertEquals(3, progress.current); assertEquals(10, progress.total)
        },
        "resolveEffectiveRadioID returns callback result when private key was imported" to {
            val original = RadioId(UUID.randomUUID()); val reconciled = RadioId(UUID.randomUUID())
            assertEquals(reconciled, resolveEffectiveRadioID(original, true) { reconciled })
        },
        "resolveEffectiveRadioID skips callback when no private key was imported" to {
            var calls = 0
            val original = RadioId(UUID.randomUUID())
            assertEquals(original, resolveEffectiveRadioID(original, false) { calls++; RadioId(UUID.randomUUID()) })
            assertEquals(0, calls, "Callback must not fire when no private key was imported")
        },
        "resolveEffectiveRadioID returns original when callback returns nil" to {
            val original = RadioId(UUID.randomUUID())
            assertEquals(original, resolveEffectiveRadioID(original, true) { null })
        },
        "resolveEffectiveRadioID handles nil callback gracefully" to {
            val original = RadioId(UUID.randomUUID())
            assertEquals(original, resolveEffectiveRadioID(original, true, null))
        },
        "Execute applies identity before channels/contacts and reports one step per write" to {
            val spy = NodeConfigExecuteSpy()
            execute(executePlan(), executeSections, spy)
            val calls = spy.calls
            val identity = calls.indexOf("importPrivateKey"); val name = calls.indexOf("setNodeName")
            val channel = calls.indexOfFirst { it.startsWith("setChannel") }; val contact = calls.indexOfFirst { it.startsWith("addContact") }
            assertTrue(identity >= 0 && name >= 0 && channel >= 0 && contact >= 0)
            assertTrue(identity < channel); assertTrue(name < channel); assertTrue(identity < contact)
            assertEquals(5, spy.progress.size)
        },
        "A first-write failure reports no progress, so the import reads as clean, not partial" to {
            val spy = NodeConfigExecuteSpy()
            assertFailsWith<NodeConfigServiceError> { execute(executePlan(), executeSections, spy, spy.writers(throwOn = { it == "importPrivateKey" })) }
            assertTrue(spy.progress.isEmpty(), "No write succeeded, so no progress must be reported")
        },
        "A mid-sequence failure reports progress only for the writes that already succeeded" to {
            val spy = NodeConfigExecuteSpy()
            assertFailsWith<NodeConfigServiceError> { execute(executePlan(), executeSections, spy, spy.writers(throwOn = { it.startsWith("setChannel") })) }
            assertEquals(listOf<ImportStep>(ImportStep.PrivateKey, ImportStep.NodeName), spy.progress)
        },
        "A local-save failure after the device add still reports the contact as applied" to {
            val spy = NodeConfigExecuteSpy()
            // Preserve the source case's writer fixture; Android's actual save-failure policy is tested separately.
            val writers = spy.writers().copy(addContact = { _, contact, added ->
                spy.record("addContact:${contact.advertisedName}")
                added()
            })
            execute(executePlan(), executeSections, spy, writers)
            assertTrue(ImportStep.Contact("C1") in spy.progress)
        },
        "Execute writes radio after contacts and tx power after radio params" to {
            val spy = NodeConfigExecuteSpy()
            execute(radioExecutePlan(), radioExecuteSections, spy)
            val calls = spy.calls
            val contact = calls.indexOfLast { it.startsWith("addContact") }
            val radio = calls.indexOf("setRadioParams"); val power = calls.indexOf("setTxPower")
            assertTrue(contact >= 0 && radio >= 0 && power >= 0)
            assertTrue(contact < radio, "Radio must be written after contacts (radio goes last)")
            assertTrue(radio < power, "TX power must follow radio params")
            assertEquals(
                listOf(ImportStep.PrivateKey, ImportStep.NodeName, ImportStep.Contact("C1"), ImportStep.RadioParameters, ImportStep.TxPower),
                spy.progress,
            )
        },
        "A tx-power failure after radio params rethrows and reports radio progress but not tx power" to {
            val spy = NodeConfigExecuteSpy()
            assertFailsWith<NodeConfigServiceError> { execute(radioExecutePlan(), radioExecuteSections, spy, spy.writers(throwOn = { it == "setTxPower" })) }
            assertTrue("setRadioParams" in spy.calls); assertTrue("setTxPower" in spy.calls)
            assertTrue(ImportStep.RadioParameters in spy.progress)
            assertFalse(ImportStep.TxPower in spy.progress, "TX power progress must not fire when setTxPower throws")
        },
        "Execute over a full plan emits exactly stepCount progress steps across all branches" to {
            val plan = fullSectionsPlan()
            val spy = NodeConfigExecuteSpy()
            execute(plan, nodeConfigAllSections, spy)
            assertEquals(NodeConfigService.stepCount(plan), spy.progress.size)
            val kinds = spy.progress.map {
                when (it) {
                    ImportStep.Position -> "position"; ImportStep.OtherParameters -> "other"; ImportStep.PrivateKey -> "privateKey"
                    ImportStep.NodeName -> "nodeName"; ImportStep.RadioParameters -> "radio"; ImportStep.TxPower -> "txPower"
                    is ImportStep.Channel -> "channel"; is ImportStep.Contact -> "contact"
                }
            }.toSet()
            assertEquals(setOf("position", "other", "privateKey", "nodeName", "radio", "txPower", "channel", "contact"), kinds)
        },
        "Pref decision gates: each setter fires only when its field differs from the device" to {
            val info = matchingSelfInfo()
            assertFalse(nodeNameNeedsWrite("Test", info)); assertTrue(nodeNameNeedsWrite("Renamed", info))
            val storedName = "a".repeat(ProtocolLimits.MAX_USABLE_NAME_BYTES)
            assertFalse(nodeNameNeedsWrite(storedName + "EXTRA", matchingSelfInfo(name = storedName)))
            assertFalse(locationNeedsWrite(ConfigImportPlan.Coordinate(47.0, -122.0), info))
            assertTrue(locationNeedsWrite(ConfigImportPlan.Coordinate(47.5, -122.0), info))
            assertFalse(radioParamsNeedWrite(radio20, info))
            assertTrue(radioParamsNeedWrite(radio20.copy(spreadingFactor = 8u), info))
            assertFalse(txPowerNeedsWrite(radio20, info))
            assertTrue(txPowerNeedsWrite(radio20.copy(txPower = 19), info))
        },
        "An unchanged config skips every pref/radio device write but still reports progress" to {
            val plan = fullSectionsPlan()
            val spy = NodeConfigExecuteSpy()
            val info = matchingSelfInfo()
            execute(plan, nodeConfigAllSections, spy, spy.writers(selfInfo = { info }))
            assertFalse("setNodeName" in spy.calls, "Unchanged name must not commit prefs")
            assertFalse("setLocation" in spy.calls, "Unchanged position must not commit prefs")
            assertFalse("setRadioParams" in spy.calls, "Unchanged radio params must not commit prefs")
            assertFalse("setTxPower" in spy.calls, "Unchanged TX power must not commit prefs")
            assertEquals(NodeConfigService.stepCount(plan), spy.progress.size)
            assertTrue(ImportStep.NodeName in spy.progress); assertTrue(ImportStep.RadioParameters in spy.progress)
            assertTrue(ImportStep.TxPower in spy.progress)
        },
        "A TX-power-only change writes only setTxPower among the radio writes" to {
            val spy = NodeConfigExecuteSpy()
            val info = matchingSelfInfo(name = "Node", txPower = 15)
            execute(radioExecutePlan(), radioExecuteSections, spy, spy.writers(selfInfo = { info }))
            assertFalse("setNodeName" in spy.calls, "Matching name must be skipped")
            assertFalse("setRadioParams" in spy.calls, "Matching radio params must be skipped")
            assertTrue("setTxPower" in spy.calls, "The differing TX power must still write")
        },
    )

    @TestFactory
    fun nativeCases() = nodeConfigNativeCases(
        "a failed device contact add does not report a confirmed contact" to {
            val spy = NodeConfigExecuteSpy()
            val plan = executePlan().copy(importPrivateKey = null, nodeName = null, channelWrites = emptyList())
            assertFailsWith<NodeConfigServiceError> {
                execute(plan, ConfigSections(contacts = true), spy, spy.writers(throwOn = { it.startsWith("addContact") }))
            }
            assertTrue(spy.progress.isEmpty())
        },
    )

    private fun serviceWith(settings: NodeConfigFakeSettings) = NodeConfigService(
        NodeConfigFakeSession(), settings, { _, _, _, _ -> }, NodeConfigContactSaver { _, _ -> }, null, NodeConfigLogger.NONE,
    )

    private fun contactJSON(keyByte: String, outPath: String, extra: String, name: String = "Test") = """
    {
        "type": 1, "name": "$name", "public_key": "${keyByte.repeat(32)}",
        "flags": 0, "latitude": "0", "longitude": "0",
        "last_advert": 0, "last_modified": 0,
        "out_path": $outPath$extra
    }
    """.trimIndent()
}

/** Records write calls and progress so execute ordering/failure can be asserted without a session. */
internal class NodeConfigExecuteSpy {
    private val lock = Any()
    private val recordedCalls = mutableListOf<String>()
    private val recordedProgress = mutableListOf<ImportStep>()
    val calls: List<String> get() = synchronized(lock) { recordedCalls.toList() }
    val progress: List<ImportStep> get() = synchronized(lock) { recordedProgress.toList() }
    fun record(label: String) = synchronized(lock) { recordedCalls += label; Unit }
    fun recordProgress(step: ImportStep) = synchronized(lock) { recordedProgress += step; Unit }

    /** Writers that record their label and throw when [throwOn] matches it. */
    fun writers(selfInfo: () -> SelfInfo = ::nodeConfigNonMatchingSelfInfo, throwOn: (String) -> Boolean = { false }): ConfigImportWriters {
        fun step(label: String) {
            record(label)
            if (throwOn(label)) throw NodeConfigServiceError.InvalidRadioSettings(RadioField.FREQUENCY)
        }
        return ConfigImportWriters(
            getSelfInfo = { record("getSelfInfo"); selfInfo() },
            importPrivateKey = { step("importPrivateKey") },
            setNodeName = { step("setNodeName") },
            setLocation = { _, _ -> step("setLocation") },
            setOtherParams = { step("setOtherParams") },
            resolveEffectiveRadioId = { original, _ -> record("resolveEffectiveRadioID"); original },
            setRadioParams = { step("setRadioParams") },
            setTxPower = { step("setTxPower") },
            setChannel = { _, write -> step("setChannel:${write.name}") },
            addContact = { _, contact, added ->
                step("addContact:${contact.advertisedName}")
                added()
            },
        )
    }
}

/** A SelfInfo whose every diff-gated field differs from the execute plans, so no gate skips. */
internal fun nodeConfigNonMatchingSelfInfo(): SelfInfo = nodeConfigSelfInfo(
    txPower = 0, radioFrequency = 1.0, radioBandwidth = 1.0, radioSpreadingFactor = 1u, radioCodingRate = 1u, name = "\u0000unset",
)
