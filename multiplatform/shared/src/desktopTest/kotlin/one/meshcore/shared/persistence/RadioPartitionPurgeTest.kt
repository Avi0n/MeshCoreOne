package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.Uuid
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioPartitionPurgeTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-purge", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun demoteDeviceToGhostRemintsIdAndStripsReachability() = runBlocking {
        val oldId = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val ghostId = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
        val radioId = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
        val publicKey = ByteArray(32) { 0x42 }
        db.deviceDao().upsert(
            DeviceEntity(
                id = oldId,
                radioID = radioId,
                publicKey = publicKey,
                nodeName = "KeepMe",
                firmwareVersion = 0,
                firmwareVersionString = "",
                manufacturerName = "",
                buildDate = "",
                maxContacts = 0,
                maxChannels = 0,
                frequency = 0,
                bandwidth = 0,
                spreadingFactor = 0,
                codingRate = 0,
                txPower = 0,
                maxTxPower = 0,
                latitude = 0.0,
                longitude = 0.0,
                blePin = 0,
                clientRepeat = false,
                pathHashMode = 0,
                manualAddContacts = false,
                autoAddConfig = 0,
                autoAddMaxHops = 0,
                multiAcks = 0,
                telemetryModeBase = 0,
                telemetryModeLoc = 0,
                telemetryModeEnv = 0,
                advertLocationPolicy = 0,
                lastConnected = 1.0,
                lastContactSync = 0,
                isActive = true,
                connectionMethods = listOf(
                    ConnectionMethod.Bluetooth(peripheralUuid = oldId, displayName = "AA:BB"),
                    ConnectionMethod.WiFi(host = "10.0.0.8", port = 5000, displayName = null),
                ),
            ),
        )

        demoteDeviceToGhost(db, oldId) { ghostId }

        assertNull(db.deviceDao().getById(oldId))
        val ghost = requireNotNull(db.deviceDao().getById(ghostId))
        assertEquals(false, ghost.isActive)
        assertTrue(ghost.connectionMethods.isEmpty())
        assertEquals(radioId, ghost.radioID)
        assertContentEquals(publicKey, ghost.publicKey)
        assertEquals("KeepMe", ghost.nodeName)
        assertEquals(1, db.deviceDao().listAll().size)
    }

    @Test
    fun purgeRadioPartitionRemovesReactionsPendingSendsAndDiscover() = runBlocking {
        val radioId = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD")
        val otherRadio = Uuid.normalized("EEEEEEEE-EEEE-EEEE-EEEE-EEEEEEEEEEEE")
        db.reactionDao().upsert(
            ReactionEntity(
                id = Uuid.normalized("11111111-1111-1111-1111-111111111111"),
                messageID = Uuid.normalized("22222222-2222-2222-2222-222222222222"),
                emoji = "👍",
                senderName = "A",
                messageHash = "h",
                rawText = "+",
                receivedAt = 1.0,
                radioID = radioId,
            ),
        )
        db.pendingSendDao().insert(
            PendingSendEntity(
                id = Uuid.normalized("33333333-3333-3333-3333-333333333333"),
                radioID = radioId,
                messageID = Uuid.normalized("44444444-4444-4444-4444-444444444444"),
                kindRawValue = 0,
                isResend = false,
                messageText = "x",
                messageTimestamp = 1,
                sequence = 1,
                enqueuedAt = 1.0,
                attemptCount = 0,
            ),
        )
        db.discoveredNodeDao().upsert(
            DiscoveredNodeEntity(
                id = Uuid.normalized("55555555-5555-5555-5555-555555555555"),
                radioID = radioId,
                publicKey = ByteArray(32) { 0x01 },
                name = "Heard",
                typeRawValue = 1,
                lastHeardEpochSeconds = 1,
                lastAdvertTimestamp = 1,
                latitude = 0.0,
                longitude = 0.0,
                outPathLength = 0,
                outPath = ByteArray(0),
            ),
        )
        db.reactionDao().upsert(
            ReactionEntity(
                id = Uuid.normalized("66666666-6666-6666-6666-666666666666"),
                messageID = Uuid.normalized("77777777-7777-7777-7777-777777777777"),
                emoji = "🎉",
                senderName = "B",
                messageHash = "o",
                rawText = "!",
                receivedAt = 1.0,
                radioID = otherRadio,
            ),
        )

        purgeRadioPartition(db, radioId)

        assertTrue(db.reactionDao().listByRadio(radioId).isEmpty())
        assertEquals(1, db.reactionDao().listByRadio(otherRadio).size)
        assertTrue(db.pendingSendDao().listByRadio(radioId).isEmpty())
        assertTrue(db.discoveredNodeDao().listByRadio(radioId).isEmpty())
    }

    @Test
    fun removeKnownRegionResetsChannelsScopedToThatRegion() = runBlocking {
        val radioId = Uuid.normalized("F0000000-0000-0000-0000-000000000001")
        val otherRadio = Uuid.normalized("F0000000-0000-0000-0000-000000000002")
        db.deviceDao().upsert(purgeDevice(radioId, knownRegions = listOf("EU", "US")))
        db.channelDao().upsert(channelOn(radioId, index = 0, scope = ChannelFloodScope.Region("EU")))
        db.channelDao().upsert(channelOn(radioId, index = 1, scope = ChannelFloodScope.Region("US")))
        db.channelDao().upsert(channelOn(otherRadio, index = 0, scope = ChannelFloodScope.Region("EU")))

        val remaining = removeDeviceKnownRegion(db, radioId, "EU")

        assertEquals(listOf("US"), remaining)
        val local = db.channelDao().listByRadio(radioId).sortedBy { it.index }
        assertEquals(ChannelFloodScope.Inherit, local[0].floodScope)
        assertEquals(ChannelFloodScope.Region("US"), local[1].floodScope)
        assertEquals(
            ChannelFloodScope.Region("EU"),
            db.channelDao().listByRadio(otherRadio).single().floodScope,
        )
    }

    @Test
    fun deviceLastConnectedReadsTheDeviceRow() = runBlocking {
        val id = Uuid.normalized("A0000000-0000-0000-0000-000000000099")
        assertNull(deviceLastConnected(db, id))
        db.deviceDao().upsert(purgeDevice(id, knownRegions = emptyList()).copy(lastConnected = 1_700.0))
        assertEquals(1_700.0, deviceLastConnected(db, id))
        assertContentEquals(ByteArray(PUBLIC_KEY_BYTES), devicePublicKey(db, id))
        assertNull(devicePublicKey(db, Uuid.normalized("A0000000-0000-0000-0000-000000000098")))
    }

    private fun purgeDevice(radioId: Uuid, knownRegions: List<String>) = DeviceEntity(
        id = radioId,
        radioID = radioId,
        publicKey = ByteArray(PUBLIC_KEY_BYTES),
        nodeName = "seed",
        firmwareVersion = 0,
        firmwareVersionString = "",
        manufacturerName = "",
        buildDate = "",
        maxContacts = 0,
        maxChannels = 0,
        frequency = 0,
        bandwidth = 0,
        spreadingFactor = 0,
        codingRate = 0,
        txPower = 0,
        maxTxPower = 0,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = false,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = true,
        knownRegions = knownRegions,
    )

    private fun channelOn(radioId: Uuid, index: Int, scope: ChannelFloodScope): ChannelEntity {
        val (mode, region) = ChannelFloodScopeStorage.decompose(scope)
        return ChannelEntity(
            id = Uuid.normalized(
                "C0000000-0000-0000-0000-${index.toString().padStart(11, '0')}${radioId.value.last()}",
            ),
            radioID = radioId,
            index = index,
            name = "ch$index",
            secret = ByteArray(CHANNEL_SECRET_BYTES),
            isEnabled = true,
            unreadCount = 0,
            floodScopeModeRawValue = mode,
            regionScope = region,
        )
    }

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
        const val CHANNEL_SECRET_BYTES = 16
    }
}
