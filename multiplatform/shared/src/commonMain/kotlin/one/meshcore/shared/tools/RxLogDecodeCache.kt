package one.meshcore.shared.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelListSink
import one.meshcore.shared.crypto.ed25519PublicToX25519
import one.meshcore.shared.meshcore.RegionResolver
import one.meshcore.shared.meshcore.RxLogDecryptor
import one.meshcore.shared.meshcore.platformRegionResolver
import one.meshcore.shared.meshcore.platformRxLogDecryptor

/**
 * Live channel secrets, DM ECDH keys, and known regions for RX log decrypt and region resolution.
 * Implements [ChannelListSink] so [one.meshcore.shared.channel.ChannelService] can push updates after sync/write.
 */
class RxLogDecodeCache(
    private val decryptor: RxLogDecryptor = platformRxLogDecryptor(),
    private val regionResolver: RegionResolver = platformRegionResolver(),
) : ChannelListSink {
    private val mutex = Mutex()
    private var channelSecrets: Map<Int, ByteArray> = emptyMap()
    private var channelNames: Map<Int, String> = emptyMap()
    private var knownRegions: List<String> = emptyList()

    private val _material = MutableStateFlow(
        DecodeSnapshot(
            channelSecrets = emptyMap(),
            channelNames = emptyMap(),
            knownRegions = emptyList(),
            decryptor = decryptor,
            regionResolver = regionResolver,
        ),
    )

    /**
     * The current decode material, re-published on every update. A durable store re-derives its rows
     * from each emission, which is what lets a late secret or region decrypt an already-logged packet.
     */
    val material: StateFlow<DecodeSnapshot> = _material.asStateFlow()

    /** First 32 bytes of the device expanded private key (Curve25519 scalar). */
    private var myPrivateKey: ByteArray? = null

    /**
     * Contact X25519 public keys grouped by the first byte of the **Ed25519** identity key
     * (matches the on-wire `srcHash` lookup used by iOS `RxLogService`).
     */
    private var contactX25519ByPrefix: Map<Int, List<ByteArray>> = emptyMap()

    override suspend fun updateChannels(channels: List<ChannelDto>) {
        mutex.withLock {
            channelSecrets = channels.associate { it.index to it.secret }
            channelNames = channels.associate { it.index to it.name }
            publishLocked()
        }
    }

    suspend fun updateKnownRegions(regions: List<String>) {
        mutex.withLock {
            if (knownRegions == regions) return
            knownRegions = regions
            publishLocked()
        }
    }

    /**
     * Device private key for DM decrypt. MeshCore export is 64 bytes
     * (`[expanded_scalar:32][nonce:32]`); only the first 32 are kept.
     */
    suspend fun updatePrivateKey(key: ByteArray?) {
        updatePrivateKeyIf(key) { true }
    }

    suspend fun updatePrivateKeyIf(key: ByteArray?, isCurrent: () -> Boolean): Boolean = mutex.withLock {
        if (!isCurrent()) return false
        myPrivateKey = key?.takeIf { it.size >= CURVE25519_KEY_SIZE }?.copyOf(CURVE25519_KEY_SIZE)
        publishLocked()
        true
    }

    /**
     * Contact **Ed25519** public keys by identity prefix byte. Converted to X25519 for ECDH.
     * Pass empty to clear.
     */
    suspend fun updateContactPublicKeys(ed25519ByPrefix: Map<Int, List<ByteArray>>) {
        updateContactPublicKeysIf(ed25519ByPrefix) { true }
    }

    suspend fun updateContactPublicKeysIf(
        ed25519ByPrefix: Map<Int, List<ByteArray>>,
        isCurrent: () -> Boolean,
    ): Boolean = mutex.withLock {
        if (!isCurrent()) return false
        val converted = mutableMapOf<Int, List<ByteArray>>()
        for ((prefix, keys) in ed25519ByPrefix) {
            val xKeys = keys.mapNotNull { ed25519PublicToX25519(it) }
            if (xKeys.isNotEmpty()) converted[prefix] = xKeys
        }
        contactX25519ByPrefix = converted
        publishLocked()
        true
    }

    suspend fun snapshot(): DecodeSnapshot = mutex.withLock { _material.value }

    private fun publishLocked() {
        _material.value = DecodeSnapshot(
            channelSecrets = channelSecrets,
            channelNames = channelNames,
            knownRegions = knownRegions,
            myPrivateKey = myPrivateKey,
            contactX25519ByPrefix = contactX25519ByPrefix,
            decryptor = decryptor,
            regionResolver = regionResolver,
        )
    }

    /**
     * Everything a row needs to be re-derived: the key material, the region names it may have been
     * transmitted under, and the [decryptor] and [regionResolver] that material is tried through.
     * Both seams travel with the material because a row can be re-derived from any store holding a
     * snapshot, and all of them must reach the same protocol implementation.
     *
     * [knownRegions] carries names, never scope keys: deriving a key from a name is protocol work
     * that happens below the bridge.
     */
    data class DecodeSnapshot(
        val channelSecrets: Map<Int, ByteArray>,
        val channelNames: Map<Int, String>,
        val knownRegions: List<String>,
        val myPrivateKey: ByteArray? = null,
        val contactX25519ByPrefix: Map<Int, List<ByteArray>> = emptyMap(),
        val decryptor: RxLogDecryptor = platformRxLogDecryptor(),
        val regionResolver: RegionResolver = platformRegionResolver(),
    )
}

/**
 * Curve25519 scalar length. MeshCore exports the device key as `[scalar:32][nonce:32]`; only the
 * scalar takes part in ECDH.
 */
private const val CURVE25519_KEY_SIZE = 32
