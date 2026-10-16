package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.NodeConfigSession
import one.meshcore.shared.settings.TelemetryModes

internal class NodeConfigImportLifetimeSession : NodeConfigSession {
    var nameReply = CompletableDeferred<Unit>()
        private set
    var nameEntered = CompletableDeferred<Unit>()
        private set
    val requestedNames = mutableListOf<String>()
    val nameWriteJobs = mutableListOf<Job>()
    var nameWriteJob: Job? = null
    var cancelledWrites = 0
    var completedWrites = 0
    var selfReads = 0
    var capabilityReads = 0

    fun prepareNextWrite() {
        check(nameWriteJob?.isCompleted == true)
        nameReply = CompletableDeferred()
        nameEntered = CompletableDeferred()
    }

    override suspend fun getSelfInfo(): SelfInfo {
        selfReads += 1
        return SelfInfo(
            advertisementType = 1,
            txPower = 22,
            maxTxPower = 30,
            publicKey = ByteArray(PUBLIC_KEY_LENGTH),
            latitude = 0.0,
            longitude = 0.0,
            multiAcks = 0,
            advertisementLocationPolicy = 0,
            telemetryModeEnvironment = 0,
            telemetryModeLocation = 0,
            telemetryModeBase = 0,
            manualAddContacts = false,
            radioFrequency = 910.525,
            radioBandwidth = 62.5,
            radioSpreadingFactor = 7,
            radioCodingRate = 5,
            name = CURRENT_NAME,
        )
    }

    override suspend fun queryDevice(): DeviceCapabilities {
        capabilityReads += 1
        return DeviceCapabilities(
            firmwareVersion = 1,
            maxContacts = 100,
            maxChannels = 4,
            blePin = 0,
            firmwareBuild = "fixture",
            model = "fixture",
            version = "fixture",
            clientRepeat = false,
            pathHashMode = 0,
        )
    }

    override suspend fun setNodeName(name: String) {
        requestedNames += name
        nameWriteJob = checkNotNull(currentCoroutineContext()[Job])
        nameWriteJobs += checkNotNull(nameWriteJob)
        nameEntered.complete(Unit)
        try {
            nameReply.await()
            completedWrites += 1
        } catch (cancelled: CancellationException) {
            cancelledWrites += 1
            throw cancelled
        }
    }

    override suspend fun exportPrivateKey(): ByteArray = unexpected("exportPrivateKey")

    override suspend fun getContacts(since: Long?): List<MeshContact> = unexpected("getContacts")

    override suspend fun getChannel(index: Int): ChannelInfo = unexpected("getChannel")

    override suspend fun importPrivateKey(privateKey: ByteArray): Unit = unexpected("importPrivateKey")

    override suspend fun setLocation(latitude: Double, longitude: Double): Unit = unexpected("setLocation")

    override suspend fun setOtherParams(
        autoAddContacts: Boolean,
        telemetryModes: TelemetryModes,
        advertLocationPolicyRaw: Int,
        multiAcks: Int,
    ): Unit = unexpected("setOtherParams")

    override suspend fun setRadioParams(
        frequencyKHz: UInt,
        bandwidthKHz: UInt,
        spreadingFactor: UByte,
        codingRate: UByte,
    ): Unit = unexpected("setRadioParams")

    override suspend fun setTxPower(txPower: Byte): Unit = unexpected("setTxPower")

    override suspend fun setChannel(radioID: Uuid, index: Int, name: String, secret: ByteArray): Unit =
        unexpected("setChannel")

    override suspend fun setContact(radioID: Uuid, contact: MeshContact): Unit = unexpected("setContact")

    private fun unexpected(operation: String): Nothing = error("Unexpected node-config operation: $operation")

    companion object {
        const val CURRENT_NAME = "Original node"
        private const val PUBLIC_KEY_LENGTH = 32
    }
}
