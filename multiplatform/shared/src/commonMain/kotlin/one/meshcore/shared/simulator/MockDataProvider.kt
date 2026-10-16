package one.meshcore.shared.simulator

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Mock catalog for simulator and Play-review demo, mirroring iOS `MockDataProvider`.
 * Seeded conversations must read as realistic: reviewers browse chats, contacts, and RX log
 * without a radio.
 */
object MockDataProvider {
    val simulatorDeviceID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000001")
    val aliceChenID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000010")
    val bobMartinezID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000020")
    val charlieNodeID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000030")
    val dianasRoomID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000040")
    val eveThompsonID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000050")
    val frankWilsonID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000060")
    val ghostNodeID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000070")
    val hannahLeeID: Uuid = Uuid.normalized("00000000-0000-0000-0000-000000000080")

    val publicChannelID: Uuid = Uuid.normalized("000000C0-0000-0000-0000-000000000000")
    val bayAreaChannelID: Uuid = Uuid.normalized("000000C0-0000-0000-0000-000000000001")
    val trailCrewChannelID: Uuid = Uuid.normalized("000000C0-0000-0000-0000-000000000002")
    val meshHQChannelID: Uuid = Uuid.normalized("000000C0-0000-0000-0000-000000000003")

    const val PUBLIC_CHANNEL_INDEX: Int = 0
    const val BAY_AREA_CHANNEL_INDEX: Int = 1
    const val TRAIL_CREW_CHANNEL_INDEX: Int = 2
    const val MESH_HQ_CHANNEL_INDEX: Int = 3
    const val MESH_HQ_TOTAL_MESSAGES: Int = 80
    const val MESH_HQ_UNREAD_COUNT: Int = 60

    val aliceReactedMessageID: Uuid = Uuid.normalized("10000000-0000-0000-0000-000000000002")
    val aliceLinkPreviewMessageID: Uuid = Uuid.normalized("10000000-0000-0000-0000-00000000000A")
    val frankRepeatMessageID: Uuid = Uuid.normalized("60000000-0000-0000-0000-000000000002")
    val frankFloodUniqueMessageID: Uuid = Uuid.normalized("60000000-0000-0000-0000-000000000004")
    val frankFloodAmbiguousMessageID: Uuid = Uuid.normalized("60000000-0000-0000-0000-000000000005")
    val bayAreaReactedMessageID: Uuid = Uuid.normalized("C1000000-0000-0000-0000-000000000002")
    val bayAreaMentionMessageID: Uuid = Uuid.normalized("C1000000-0000-0000-0000-000000000003")
    val publicAmbiguousRegionMessageID: Uuid = Uuid.normalized("C0000000-0000-0000-0000-000000000005")
    val uniqueRxLogEntryID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    val ambiguousRxLogEntryID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000002")

    const val UNIQUE_REGION_NAME: String = "US915"
    val ambiguousRegionNames: List<String> = listOf("de-by", "de-hh")

    const val INLINE_IMAGE_URL: String = "https://meshcoreone.com/summit.jpg"
    const val SIMULATOR_DEVICE_NAME: String = "MeshCore One Sim"

    /** Transport handle stored as last-connection; equals [simulatorDeviceID]. */
    val SIMULATOR_HANDLE: String = simulatorDeviceID.value

    private const val PUBLIC_KEY_SIZE: Int = 32
    private const val CHANNEL_SECRET_SIZE: Int = 16
    private const val BYTE_MASK: Int = 0xFF

    fun mockPublicKey(seed: Int): ByteArray = ByteArray(PUBLIC_KEY_SIZE) { index -> wrappingByte(index + seed) }

    fun channelSecret(seed: Int): ByteArray = ByteArray(CHANNEL_SECRET_SIZE) { index -> wrappingByte(index + seed) }

    fun wrappingByte(value: Int): Byte = (value and BYTE_MASK).toByte()

    fun isSimulatorDeviceId(id: Uuid): Boolean = id == simulatorDeviceID

    fun simulatorDevice(nowEpochSeconds: Double): DeviceEntity = DeviceEntity(
        id = simulatorDeviceID,
        radioID = simulatorDeviceID,
        publicKey = mockPublicKey(seed = 1),
        nodeName = "Sim",
        firmwareVersion = 8,
        firmwareVersionString = "v1.11.0",
        manufacturerName = "Mock Device",
        buildDate = "2025-12-20",
        maxContacts = 100,
        maxChannels = 8,
        frequency = 915_000,
        bandwidth = 250_000,
        spreadingFactor = 10,
        codingRate = 5,
        txPower = 20,
        maxTxPower = 20,
        latitude = 37.7749,
        longitude = -122.4194,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = false,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 2,
        telemetryModeBase = 2,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = nowEpochSeconds,
        lastContactSync = 0,
        isActive = true,
    )

    @OptIn(ExperimentalEncodingApi::class)
    val demoImageData: ByteArray = Base64.decode(DEMO_IMAGE_BASE64)

    // 120x80 gradient PNG used for the offline link-preview hero and inline image.
    private const val DEMO_IMAGE_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAHgAAABQCAIAAABd+SbeAAAE00lEQVR42u3Q1Y4dVBiA0f0gXHDFo0AIIYQQAiGkIaS0pVBK" +
            "KW0ppUNl9Iy7u7u7u7u7u7tVaGbSyekc+78bQshO1hMsdRLwodwp8YJ4SbwiXv83qEP/j84dEcfEidgp8YJ4SbwiXsuofb+P" +
            "jR0Qh8QRcUyciJ0SL4iXtqhd309M7RH7xAFxSBwRx8SJ2KllatvnUyt2xHaJPWKfOCAOiSPi2ITa9P7Mpi1im9gR2yX2iH3i" +
            "gDg0ota9PhfaIDaJLWKb2BHbJfaI/TNqxfOLVWKNWCc2iE1ii9gmdsTUkseXF5aJFbFVYo1YJzaITWJLQC14fHXJIrFELBMr" +
            "YqvEGrFObFil5ty/NmueWCAWiSVimVgRWyXWLFAzbt9YMUvMEfPEArFILBHLxMq71JTrtxLTxIzYLDFHzBMLxCKx9JaaMFyR" +
            "mySmiGliRmyWmCPmiQXDFTXq8t0bY8Q4MUFMElPENDFDzMqoYefvjY0Qo2JjxDgxQUwSU8S0LWrQ6aqpIWKYGCFGxcaIcWKC" +
            "mLRM9Ttes2SAGCSGiGFihBgVGyPGzVG9jjes6yP6iQFikBgihokRYtSI6na4KdRD9Ir1Ef3EADFIDBHDZ1SH/Y+dRBfRTfQQ" +
            "vWJ9RD8xQKi257fOtRMdRCfRRXQTPUSvWJ+Manl2+5JWsTaineggOokuopvosUo1Pb1jVjPRQrSKtRHtRAfRSXRZoBqe3LWi" +
            "kWgimokWolWsjWgnOkyour/v2VRPNBCNRBPRTLQQrWJtRlSN3QO5WrE6op5oIBqJJqKZaLF7oCrtHr5RRVQTNUQtUSdWTzQQ" +
            "jWKq/PGjCxVEJVFFVBM1RC1RJ1YvoEr/emyqTKycqCAqiSqimqghai1TxY/sLCkhSokysXKigqgkqohqc1Thn0+sKyKKiRKi" +
            "lCgTKycqiMp3qfyHzyQKiEKiiCgmSohSokys/C2V84d9LpEnlk8UEIVEEVFMlBAq64HjuWwih8gl8sTyiQKikCiSURn3nY1l" +
            "EllENpFD5BJ5YvlEgS0q7b7BVDqRQWQSWUQ2kUPkEnkWqJR7blakEmli6UQGkUlkEdlEjgmV9LuHTclECpFKpImlExlEJpFl" +
            "RCXc9RJKJJKIZCKFSCXSxNKJjDMq9jfvN+KIeLEEIpFIIpKJFCLVTEXf8b0QQ8QScUS8WAKRSCQRyQIq8lf/S6KIaCKGiCXi" +
            "iHixBCLRKhV+O9CsCCKSiCKiiRgilogj4s1RobeDrQsjwsUiiEgiiogmYojYd6ngX0IlQohQIowIF4sgIokoIvotFXgrXC6I" +
            "CCZCiFAijAgXiyAib4Urv58jz/mLBRCBRBARTIQQoUSYjPL5KdqYL+FH+IsFEIFEEBFMhNiivG7GmvImfAhfwo/wFwsgAokg" +
            "y5THj3GWeBJehDfhQ/gSfoS/WIA5yu1Ggk3uYh6EJ+FFeBM+hC/hZ0QZricJuRJuhLuYB+FJeBHehM8Z5XQ9xZlwIQyEK+FG" +
            "uBMeYp6EcriWdsFRzIlwJlwIA+FKuBHuAur5DxmX2BMOhKOYE+FMuBAGwtUq9fRqllnPiOeEPeFAOIo5Ec6EiwXqvQ/e1/4F" +
            "OlpH62hNR+toHa0LdLSO1nS0jtbRekFH62hNR+toHa0XdLSO1nS0jtbRmo7+//gHeCggAUg2kXEAAAAASUVORK5CYII="
}
