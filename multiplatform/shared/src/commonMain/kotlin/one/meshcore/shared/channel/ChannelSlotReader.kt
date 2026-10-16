package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.settings.PersistentLogger
import kotlin.random.Random

/** One-slot read, including the transient-timeout retry `fetchChannel` performs. */
interface ChannelSlotReading {
    /**
     * Reads a single slot with retry for transient failures. A device `NOT_FOUND` means the slot is
     * unconfigured (null, non-retryable); a response timeout backs off `500·2^(n-1)` ms ±100 and retries;
     * any other device/timeout failure throws wrapped as [ChannelServiceError.SessionError]. Transport and
     * send-timeout failures propagate raw so the sync loop classifies them. A returned index other than the
     * one requested throws [ChannelServiceError.InvalidChannelIndex]. Mirrors `fetchChannel(index:)`.
     */
    suspend fun fetchChannel(index: Int): ChannelInfo?
}

internal class ChannelSlotReader(private val session: ChannelSession, private val logger: PersistentLogger?) :
    ChannelSlotReading {
    /** The result of reading one slot: configured (keep), unconfigured (clean), or a classified failure. */
    sealed interface SlotOutcome {
        data class Configured(val info: ChannelInfo) : SlotOutcome

        data object Unconfigured : SlotOutcome

        data class Failed(val error: ChannelSyncError) : SlotOutcome
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun readSlot(index: Int, logFailure: Boolean = true): SlotOutcome = try {
        val info = fetchChannel(index)
        if (info != null) SlotOutcome.Configured(info) else SlotOutcome.Unconfigured
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val classified = ChannelSyncClassifier.classifyError(error, index)
        if (logFailure) logger?.warning("Failed to sync channel $index: ${classified.description}")
        SlotOutcome.Failed(classified)
    }

    override suspend fun fetchChannel(index: Int): ChannelInfo? {
        for (attempt in 1..FETCH_MAX_ATTEMPTS) {
            try {
                val info = session.getChannel(index)
                if (info.index != index) {
                    logger?.error(
                        "Channel index mismatch: requested $index, received ${info.index}",
                    )
                    mismatchedChannelIndex()
                }
                return configuredChannel(index, info)
            } catch (e: ChannelDeviceException) {
                if (e.firmwareCode == ChannelProtocolErrorCode.NOT_FOUND) return null
                failChannelFetch(e)
            } catch (e: ChannelTimeoutException) {
                if (attempt < FETCH_MAX_ATTEMPTS) {
                    val base = FETCH_BASE_BACKOFF_MS * (1L shl (attempt - 1))
                    val jitter = Random.nextLong(-FETCH_JITTER_MS, FETCH_JITTER_MS + 1)
                    val delayMs = base + jitter
                    logger?.info(
                        "Channel $index fetch timeout, retry $attempt/$FETCH_MAX_ATTEMPTS in ${delayMs}ms",
                    )
                    delay(delayMs)
                } else {
                    failChannelFetch(e)
                }
            }
        }
        failChannelFetch(ChannelTimeoutException())
    }

    private fun mismatchedChannelIndex(): Nothing = throw ChannelServiceError.InvalidChannelIndex()

    private fun failChannelFetch(error: Throwable): Nothing = throw ChannelServiceError.SessionError(error)

    private fun configuredChannel(index: Int, info: ChannelInfo): ChannelInfo? {
        if (!ChannelSecret.isChannelConfigured(info.name, info.secret)) return null
        if (info.name.isEmpty()) {
            logger?.warning("Channel $index has empty name with non-zero secret; treating as configured")
        }
        return info
    }

    private companion object {
        const val FETCH_MAX_ATTEMPTS = 3
        const val FETCH_BASE_BACKOFF_MS = 500L
        const val FETCH_JITTER_MS = 100L
    }
}
