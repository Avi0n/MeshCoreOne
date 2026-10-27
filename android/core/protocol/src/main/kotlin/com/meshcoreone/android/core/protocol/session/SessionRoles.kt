// PortedFrom: MeshCore/Sources/MeshCore/Protocols/AdvertisingSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/ChannelSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/ConfigurationSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/ContactSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/DiagnosticsSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/MeshCoreSessionProtocol.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/MessageFetchSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/MessagingSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/RemoteAccessSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/SessionEventStreaming.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.model.*
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface SessionEventStreaming {
    val connectionState: Flow<ConnectionState>
    fun events(): Flow<MeshEvent>
    fun events(filter: EventFilter): Flow<MeshEvent>
    suspend fun waitForEvent(filter: EventFilter, timeout: Double? = null): MeshEvent?
}

interface MessagingSessionOps {
    val currentSelfInfo: SelfInfo?
    suspend fun sendMessage(
        destination: Bytes, text: String, timestamp: Instant = Instant.now(), attempt: UByte = 0u,
    ): MessageSentInfo
    suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant = Instant.now())
}

interface ContactSessionOps {
    suspend fun getContacts(since: Instant? = null): List<MeshContact>
    /** A terminal partial fetch retains its reported total but cannot establish a complete cache baseline. */
    suspend fun getContactsReportingTotal(since: Instant? = null): ContactFetchResult
    /** Only NOT_FOUND returns null; other device errors propagate with their raw code. */
    suspend fun getContact(publicKey: Bytes): MeshContact?
    suspend fun addContact(contact: MeshContact)
    suspend fun removeContact(publicKey: Bytes)
    suspend fun resetPath(publicKey: Bytes)
    suspend fun sendPathDiscovery(destination: Bytes): MessageSentInfo
    suspend fun shareContact(publicKey: Bytes)
    /** A requested key must identify the leading key in the exported card, not an embedded payload value. */
    suspend fun exportContact(publicKey: Bytes? = null): String
    suspend fun importContact(cardData: Bytes)
    suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags)
}

interface ChannelSessionOps {
    /**
     * An unanswered earlier read of this index fails with ConnectionLost/UnresolvedReply before sending.
     * Recover through the connection owner with a real disconnect/reconnect, not a same-link logical restart.
     */
    suspend fun getChannel(index: UByte): ChannelInfo
    /** Missing sent indices remain uncorrelated; preserve unread slots and reconnect before retrying them. */
    suspend fun getChannels(indices: List<UByte>): ChannelFetchResult
    suspend fun setChannel(index: UByte, name: String, secret: Bytes)
}

interface MessageFetchSessionOps {
    suspend fun getMessage(timeout: Double? = null): MessageResult
    suspend fun startAutoMessageFetching()
    suspend fun stopAutoMessageFetching()
}

interface MeshCoreSessionProtocol :
    SessionEventStreaming, MessagingSessionOps, ContactSessionOps, ChannelSessionOps, MessageFetchSessionOps

interface AdvertisingSessionOps {
    suspend fun sendAdvertisement(flood: Boolean = false)
    suspend fun setName(name: String)
    suspend fun setCoordinates(latitude: Double, longitude: Double)
    suspend fun getContact(publicKey: Bytes): MeshContact?
}

interface ConfigurationSessionOps {
    suspend fun sendAppStart(): SelfInfo
    suspend fun queryDevice(): DeviceCapabilities
    suspend fun getBattery(): BatteryInfo
    suspend fun getTime(): Instant
    suspend fun setTime(date: Instant)
    suspend fun setName(name: String)
    suspend fun setCoordinates(latitude: Double, longitude: Double)
    suspend fun setTxPower(power: Byte)
    suspend fun setRadio(
        frequency: Double, bandwidth: Double, spreadingFactor: UByte, codingRate: UByte,
        clientRepeat: Boolean? = null,
    )
    suspend fun getRepeatFreq(): List<FrequencyRange>
    suspend fun setOtherParams(
        manualAddContacts: Boolean, telemetryModeEnvironment: UByte, telemetryModeLocation: UByte,
        telemetryModeBase: UByte, advertisementLocationPolicy: UByte, multiAcks: UByte? = null,
    )
    suspend fun setDevicePin(pin: UInt)
    suspend fun getAutoAddConfig(): AutoAddConfig
    suspend fun setAutoAddConfig(config: AutoAddConfig)
    suspend fun setPathHashMode(mode: UByte)
    suspend fun setDefaultFloodScope(name: String, scope: FloodScope)
    suspend fun getDefaultFloodScope(): DefaultFloodScope?
    suspend fun reboot()
    suspend fun factoryReset()
    suspend fun getStatsCore(): CoreStats
    suspend fun getStatsRadio(): RadioStats
    suspend fun getStatsPackets(): PacketStats
    suspend fun getCustomVars(): Map<String, String>
    suspend fun setCustomVar(key: String, value: String)
    suspend fun exportPrivateKey(): Bytes
    suspend fun importPrivateKey(key: Bytes)
    suspend fun sign(data: Bytes, chunkSize: Long = 120, timeout: Double? = null): Bytes
}

interface DiagnosticsSessionOps {
    suspend fun requestStatus(publicKey: Bytes): StatusResponse
    suspend fun requestStatus(publicKey: Bytes, type: ContactType): StatusResponse
    suspend fun requestTelemetry(publicKey: Bytes): TelemetryResponse
    suspend fun requestNeighbours(
        publicKey: Bytes, count: UByte = 255u, offset: UShort = 0u, orderBy: UByte = 0u,
        pubkeyPrefixLength: UByte = 4u,
    ): NeighboursResponse
    suspend fun fetchAllNeighbours(
        publicKey: Bytes, orderBy: UByte = 0u, pubkeyPrefixLength: UByte = 4u,
    ): NeighboursResponse
    suspend fun requestMMA(publicKey: Bytes, start: Instant, end: Instant): MMAResponse
    suspend fun requestACL(publicKey: Bytes): ACLResponse
    suspend fun getSelfTelemetry(): TelemetryResponse
    suspend fun sendPathDiscovery(destination: Bytes): MessageSentInfo
    suspend fun sendTrace(tag: UInt? = null, authCode: UInt? = null, flags: UByte = 0u, path: Bytes? = null): MessageSentInfo
}

interface RemoteAccessSessionOps {
    suspend fun sendLogin(destination: Bytes, password: String): MessageSentInfo
    suspend fun sendLogout(destination: Bytes)
    suspend fun sendCommand(destination: Bytes, command: String, timestamp: Instant = Instant.now()): MessageSentInfo
    suspend fun sendKeepAlive(publicKey: Bytes, syncSince: UInt): MessageSentInfo
    suspend fun requestOwnerInfo(publicKey: Bytes): OwnerInfoResponse
    suspend fun requestStatus(publicKey: Bytes, type: ContactType): StatusResponse
    suspend fun requestTelemetry(publicKey: Bytes): TelemetryResponse
    suspend fun requestNeighbours(
        publicKey: Bytes, count: UByte, offset: UShort, orderBy: UByte, pubkeyPrefixLength: UByte,
    ): NeighboursResponse
    suspend fun getMessage(timeout: Double?): MessageResult
    suspend fun sendMessageWithRetry(
        destination: Bytes, text: String, timestamp: Instant = Instant.now(),
        maxAttempts: Long = 3, floodAfter: Long = 2, maxFloodAttempts: Long = 2, timeout: Double? = null,
    ): MessageSentInfo?
    suspend fun sendPathDiscovery(destination: Bytes): MessageSentInfo
}
