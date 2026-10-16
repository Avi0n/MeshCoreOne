package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.formatRoadDistanceMeters
import one.meshcore.shared.map.hasUsableCoordinate
import one.meshcore.shared.map.haversineMeters
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.prefixHex
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.remotenode.LocalizedLengthSymbols
import one.meshcore.shared.remotenode.MeasurementSystem

/**
 * Pure, framework-neutral presentation helpers over a [ContactDto], ported from the iOS `ContactDTO`
 * computed properties. Kept out of the Compose layer so the row layout, filter, and sort all read the
 * same derived values and the contact-list view model stays unit-testable on the desktop JVM.
 */

/** The name shown for a contact: the local nickname when set, else the advertised name. */
val ContactDto.displayName: String get() = nickname ?: name

/** Phone-clock last heard on air, or null when the radio has never stamped one. */
val ContactDto.heardOnAirSeconds: Long?
    get() = lastHeardTimestamp?.takeIf { it > 0 }

/** The full public key as uppercase hex. */
val ContactDto.publicKeyHex: String get() = publicKey.toUppercaseHex()

/** The typed contact kind, falling back to [ContactType.CHAT] for an unknown raw value. */
val ContactDto.contactType: ContactType get() = ContactType.fromRaw(typeRawValue) ?: ContactType.CHAT

/**
 * The leading [hashSize] bytes of the public key as uppercase hex — the monospaced id prefix shown at
 * the head of a row. [hashSize] is the connected radio's key-prefix display size (iOS
 * `connectedDevice?.hashSize ?? 1`).
 */
fun ContactDto.idPrefixHex(hashSize: Int = DEFAULT_HASH_SIZE): String = publicKey.prefixHex(hashSize)

/** True when the out-path is the flood-routing sentinel (`0xFF`). Mirrors iOS `isFloodRouted`. */
val ContactDto.isFloodRouted: Boolean get() = floodPath(outPathLength)

/** The number of hops in the deliberately-set out-path (0 when flood-routed or undecodable). */
val ContactDto.pathHopCount: Int get() = decodedPathHopCount(outPathLength)

/**
 * The hop count to surface: the out-path hops when a route exists, else the passively-heard
 * [inboundHopCount] (looked up live from the discovered-node table). `null` when flood-routed and no
 * inbound hop is known. Mirrors iOS `displayedHopCount(inboundHopCount:)`.
 */
fun ContactDto.displayedHopCount(inboundHopCount: Int?): Int? =
    displayedHopCount(isFloodRouted, pathHopCount, inboundHopCount)

/**
 * Whether the contact carries a usable coordinate: a non-zero lat/lon that is also a valid WGS-84
 * coordinate. Mirrors iOS `hasLocation` (`CLLocationCoordinate2DIsValid`).
 */
val ContactDto.hasLocation: Boolean
    get() = hasUsableCoordinate(latitude, longitude)

/**
 * The avatar glyph text for an identity (chat) contact: the first emoji in the name if any; else the
 * first letters of the first two space-split words uppercased; else the first character uppercased.
 * Mirrors iOS `ContactAvatar.initials`. Emoji detection is an approximation of Swift's grapheme-level
 * `Character.isEmoji`, covering the common pictographic ranges.
 */
val ContactDto.avatarInitials: String get() = avatarInitials(displayName)

/** [ContactDto.avatarInitials] over a bare name, for rows that have no contact row yet (Discover). */
fun avatarInitials(name: String): String {
    firstEmojiOrNull(name)?.let { return it }
    val words = name.split(' ').filter { it.isNotEmpty() }
    return if (words.size >= 2) {
        (words[0].take(1) + words[1].take(1)).uppercase()
    } else {
        name.take(1).uppercase()
    }
}

/** The route descriptor a row shows: Direct (zero-hop out-path), N hops, or Flood. */
sealed interface ContactRouteKind {
    data object Direct : ContactRouteKind
    data class Hops(val count: Int) : ContactRouteKind
    data object Flood : ContactRouteKind
}

/** The hop count to surface: out-path hops when a route exists, otherwise the inbound advert hops. */
internal fun displayedHopCount(floodRouted: Boolean, hopCount: Int, inboundHopCount: Int?): Int? =
    if (floodRouted) inboundHopCount else hopCount

/** A set non-flood zero-hop path is Direct; otherwise a known hop count; otherwise Flood. */
internal fun routeKindFor(floodRouted: Boolean, hopCount: Int, displayedHopCount: Int?): ContactRouteKind = when {
    !floodRouted && hopCount == 0 -> ContactRouteKind.Direct
    else -> displayedHopCount?.let { ContactRouteKind.Hops(it) } ?: ContactRouteKind.Flood
}

/**
 * The route label kind: a set non-flood zero-hop path is Direct; otherwise a known [displayedHopCount]
 * is N hops; otherwise Flood. Mirrors the iOS `ContactRowView.routeLabel` branches.
 */
fun ContactDto.routeKind(inboundHopCount: Int?): ContactRouteKind =
    routeKindFor(isFloodRouted, pathHopCount, displayedHopCount(inboundHopCount))

/**
 * The unit bucket a compact relative-age label renders, with the count to substitute into that unit's
 * template. Bucketing is kept separate from wording so every age string comes from a localized resource
 * rather than from Kotlin; [relativeAgeLabel] resolves a bucket to text. A week or more matches iOS
 * `RelativeTimestampText` and uses the calendar month and day instead of a week count.
 */
sealed interface RelativeAge {
    data object Now : RelativeAge
    data class Minutes(val count: Long) : RelativeAge
    data class Hours(val count: Long) : RelativeAge
    data class Days(val count: Long) : RelativeAge
    data object OlderThanAWeek : RelativeAge
}

/**
 * The relative-age bucket for a row's timestamp. [timestampSeconds] and [nowSeconds] are epoch seconds;
 * a timestamp ahead of [nowSeconds] clamps to [RelativeAge.Now] rather than reading as a negative age.
 * Mirrors iOS `RelativeTimestampText.format`: now under a minute, then abbreviated units, then month+day
 * once the interval reaches a week.
 */
fun relativeAge(timestampSeconds: Long, nowSeconds: Long): RelativeAge {
    val elapsed = (nowSeconds - timestampSeconds).coerceAtLeast(0)
    return when {
        elapsed < SECONDS_PER_MINUTE -> RelativeAge.Now
        elapsed < SECONDS_PER_HOUR -> RelativeAge.Minutes(elapsed / SECONDS_PER_MINUTE)
        elapsed < SECONDS_PER_DAY -> RelativeAge.Hours(elapsed / SECONDS_PER_HOUR)
        elapsed < SECONDS_PER_WEEK -> RelativeAge.Days(elapsed / SECONDS_PER_DAY)
        else -> RelativeAge.OlderThanAWeek
    }
}

/** A user location used for distance sorting and the row's "X away" label. */
data class UserLocation(val latitude: Double, val longitude: Double)

/** Great-circle distance in meters from [from] to this contact's coordinate (haversine, WGS-84 mean radius). */
fun ContactDto.distanceMeters(from: UserLocation): Double =
    haversineMeters(LatLng(from.latitude, from.longitude), LatLng(latitude, longitude))

/**
 * The abbreviated road distance shown next to a located row, e.g. `850 m` or `0.5 mi`. Uses the same
 * locale road formatter as the map and Line of Sight so a US locale does not keep kilometers on the
 * contact list while the map scale is in miles.
 */
fun formatRoadDistance(
    meters: Double,
    system: MeasurementSystem,
    symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
): String = formatRoadDistanceMeters(meters, system, symbols)

/**
 * The formatted road distance to this contact, or `null` when [userLocation] is missing or the contact
 * carries no usable coordinate. Mirrors iOS `ContactRowView.distanceToContact`.
 */
fun ContactDto.distanceLabel(
    userLocation: UserLocation?,
    system: MeasurementSystem = MeasurementSystem.METRIC,
    symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
): String? {
    if (userLocation == null || !hasLocation) return null
    return formatRoadDistance(distanceMeters(userLocation), system, symbols)
}

/** The key-prefix width a caller with no connected radio falls back to (iOS `?? 1`). */
const val DEFAULT_HASH_SIZE = 1

// Relative-age thresholds (seconds).
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_WEEK = 604_800L
