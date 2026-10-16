package one.meshcore.shared.map

import kotlin.math.abs

/**
 * Maps an SNR value to a trace [LineStyle]. Note the collapse: [SnrQuality.EXCELLENT] and
 * [SnrQuality.GOOD] both map to [LineStyle.TRACE_GOOD] — the line palette is 4-tier while the quality
 * enum is 5-tier. Mirrors iOS `MapLine.LineStyle.forSNR`.
 */
fun lineStyleForSnr(snr: Double?): LineStyle = when (snrQuality(snr)) {
    SnrQuality.EXCELLENT, SnrQuality.GOOD -> LineStyle.TRACE_GOOD
    SnrQuality.FAIR -> LineStyle.TRACE_MEDIUM
    SnrQuality.POOR -> LineStyle.TRACE_WEAK
    SnrQuality.UNKNOWN -> LineStyle.TRACE_UNTRACED
}

/**
 * The midpoint distance/SNR badge label of the form "<distance>, <snr> <unit>". The separator and the
 * space before the unit are composed here, not by the formatter.
 */
fun snrBadgeText(distanceMeters: Double, snr: Double, formatter: SnrBadgeFormatter): String {
    val distance = formatter.formatDistance(distanceMeters)
    val snrValue = formatter.formatSnr(snr)
    return "$distance, $snrValue ${formatter.snrUnit}"
}

/**
 * The midpoint distance/SNR badge pin for the link between two coordinates, shared by the trace and
 * neighbor SNR maps. Callers pass [id] so the trace map keeps its deterministic id for diffing while
 * the neighbor map mints a fresh one. Mirrors iOS `MapLine.snrBadge`.
 */
fun snrBadge(id: String, from: LatLng, to: LatLng, snr: Double, formatter: SnrBadgeFormatter): MapPoint = MapPoint(
    id = id,
    coordinate = snrBadgeMidpoint(from, to),
    pinStyle = PinStyle.BADGE,
    label = null,
    isClusterable = false,
    hopIndex = null,
    badgeText = snrBadgeText(distanceMeters = haversineMeters(from, to), snr = snr, formatter = formatter),
)

/**
 * Geographic midpoint of two coordinates, shifting one longitude by 360° before averaging when the
 * pair straddles the antimeridian (|Δlon| > 180) so the badge lands between them rather than on the
 * opposite hemisphere. Mirrors iOS `MapLine.midpoint`.
 */
fun snrBadgeMidpoint(from: LatLng, to: LatLng): LatLng {
    var lon1 = from.longitude
    var lon2 = to.longitude
    if (abs(lon1 - lon2) > ANTIMERIDIAN_DELTA) {
        if (lon1 < lon2) lon1 += FULL_LONGITUDE else lon2 += FULL_LONGITUDE
    }
    var midLongitude = (lon1 + lon2) / 2
    if (midLongitude > MAX_LONGITUDE) midLongitude -= FULL_LONGITUDE
    return LatLng(
        latitude = (from.latitude + to.latitude) / 2,
        longitude = midLongitude,
    )
}

private const val ANTIMERIDIAN_DELTA = 180.0
private const val FULL_LONGITUDE = 360.0
