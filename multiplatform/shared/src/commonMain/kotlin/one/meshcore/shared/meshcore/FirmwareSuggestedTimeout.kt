package one.meshcore.shared.meshcore

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Sanitizes the firmware `suggested_timeout_ms` hint carried on [MessageSentInfo], used by trace,
 * zero-hop ping, and flood path discovery. Scales the hint for slack and clamps it into a
 * per-use-case band; a missing hint (0) yields the profile default so the wait cannot expire
 * immediately.
 *
 * One type for every caller on purpose: the same policy was previously written out three times and
 * the trace copy silently lost the scale and the grace, so a flood trace gave up while the radio
 * was still relaying.
 */
object FirmwareSuggestedTimeout {
    /** Slack factor on the raw hint, before any profile grace. */
    const val MULTIPLIER: Double = 1.2

    private const val MILLIS_PER_SECOND = 1_000.0

    /** Floor for the overall Discover-Path wait; flood `est_timeout` underestimates multi-hop return. */
    const val PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS: Double = 20.0

    /** Retransmit spacing multiplier on the firmware estimate; headroom leaves room for a first reply. */
    private const val RETRANSMIT_RTT_HEADROOM = 2

    /** Minimum spacing between resends; flood is expensive, so never pace faster than this. */
    private const val RETRANSMIT_FLOOR_MS = 5_000L

    /**
     * Per-use-case bounds. A single-neighbour round trip and a mesh-wide flood need different bands,
     * so callers pick the profile matching their send. [graceSeconds] is fixed slack added to the
     * scaled hint before clamping: the hint covers outbound airtime only, while the reply re-crosses
     * the mesh with delays the estimate does not include.
     */
    data class Profile(
        val minimumSeconds: Double,
        val defaultSeconds: Double,
        val maximumSeconds: Double,
        val graceSeconds: Double,
    ) {
        companion object {
            /** Single-neighbour ping to a direct contact; honours a small valid hint instead of inflating it. */
            val ZERO_HOP: Profile = Profile(
                minimumSeconds = 1.0,
                defaultSeconds = 5.0,
                maximumSeconds = 30.0,
                graceSeconds = 0.0,
            )

            /** Flood path discovery and multi-hop traces; grace covers return-leg jitter. */
            val FLOOD: Profile = Profile(
                minimumSeconds = 5.0,
                defaultSeconds = 30.0,
                maximumSeconds = 60.0,
                graceSeconds = 8.0,
            )
        }
    }

    /** Scaled hint plus profile grace, clamped into the profile band; a missing hint (0) yields the default. */
    fun sanitizedSeconds(suggestedTimeoutMs: Long, profile: Profile): Double {
        if (suggestedTimeoutMs <= 0) return profile.defaultSeconds
        val candidate = suggestedTimeoutMs / MILLIS_PER_SECOND * MULTIPLIER + profile.graceSeconds
        return candidate.coerceIn(profile.minimumSeconds, profile.maximumSeconds)
    }

    /** [sanitizedSeconds] in milliseconds, for callers that wait on a millisecond budget. */
    fun sanitizedMs(suggestedTimeoutMs: Long, profile: Profile): Long =
        (sanitizedSeconds(suggestedTimeoutMs, profile) * MILLIS_PER_SECOND).roundToLong()

    /** Overall Discover-Path wait: the flood-sanitized hint, at least [PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS]. */
    fun pathDiscoverySeconds(suggestedTimeoutMs: Long): Double =
        max(PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS, sanitizedSeconds(suggestedTimeoutMs, Profile.FLOOD))

    /** Spacing between resends, or `null` when firmware gave no hint (one send for the whole budget). */
    fun retransmitIntervalMs(suggestedTimeoutMs: Long): Long? {
        if (suggestedTimeoutMs <= 0) return null
        return max(RETRANSMIT_FLOOR_MS, suggestedTimeoutMs * RETRANSMIT_RTT_HEADROOM)
    }
}
