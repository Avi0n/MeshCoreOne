package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/** iOS `ChannelJoinFloodScopeApplier.applyIfNeeded` persist-fail dump. */
const val FAILED_PERSIST_CHANNEL_FLOOD_AFTER_JOIN =
    "Failed to persist channel flood scope after join"

/**
 * Applies a channel's local flood-scope preference after a join from a `meshcore://channel/add` deep link
 * or hashtag carrying `region_scope`. The Kotlin analogue of the iOS `ChannelJoinFloodScopeApplier`: it
 * writes only the app-side per-channel preference, never the session (global) flood scope. A failed write
 * is swallowed so the radio join still completes with the channel's default `inherit` preference.
 *
 * The `region_scope` value arrives already trimmed and byte-capped by the parser, so a non-empty value maps
 * straight to [ChannelFloodScope.Region].
 */
object ChannelJoinFloodScopeApplier {
    /** Resolves a `region_scope` value to a preference: a non-empty scope pins [ChannelFloodScope.Region]. */
    fun preferredFloodScope(regionScope: String?): ChannelFloodScope? =
        regionScope?.takeIf { it.isNotEmpty() }?.let { ChannelFloodScope.Region(it) }

    /**
     * Persists [ChannelFloodScope.Region] for a non-empty [regionScope] and returns the channel with the
     * preference reflected; a null/empty scope or a failed write returns the channel unchanged.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun applyIfNeeded(
        channel: ChannelDto,
        regionScope: String?,
        logger: PersistentLogger? = null,
        setFloodScope: suspend (Uuid, ChannelFloodScope) -> Unit,
    ): ChannelDto {
        val preferred = preferredFloodScope(regionScope) ?: return channel
        return try {
            setFloodScope(channel.id, preferred)
            val (mode, region) = ChannelFloodScopeStorage.decompose(preferred)
            channel.copy(floodScopeModeRawValue = mode, regionScope = region)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger?.error("$FAILED_PERSIST_CHANNEL_FLOOD_AFTER_JOIN: ${error.message ?: error}")
            channel
        }
    }
}
