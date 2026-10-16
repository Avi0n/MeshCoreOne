package one.meshcore.shared.rf

/**
 * Builds a sea-level (0 m) elevation sample list between two coordinates for RF analysis when
 * real terrain elevation is unavailable (pre-MapLibre elevation service). Sample count is clamped
 * so short paths still have at least two endpoints.
 */
fun flatElevationProfile(
    pointA: GeoCoordinate,
    pointB: GeoCoordinate,
    sampleCount: Int = DEFAULT_FLAT_SAMPLE_COUNT,
): List<ElevationSample> {
    val count = sampleCount.coerceAtLeast(MIN_SAMPLE_COUNT)
    val totalDistance = RFCalculator.distance(pointA, pointB)
    if (totalDistance <= 0.0) {
        return listOf(
            ElevationSample(pointA, elevation = 0.0, distanceFromAMeters = 0.0),
            ElevationSample(pointB, elevation = 0.0, distanceFromAMeters = 0.0),
        )
    }
    return List(count) { index ->
        val fraction = index.toDouble() / (count - 1).toDouble()
        val lat = pointA.latitude + (pointB.latitude - pointA.latitude) * fraction
        val lon = pointA.longitude + (pointB.longitude - pointA.longitude) * fraction
        ElevationSample(
            coordinate = GeoCoordinate(latitude = lat, longitude = lon),
            elevation = 0.0,
            distanceFromAMeters = totalDistance * fraction,
        )
    }
}

/** Parses LOS form fields into coordinates and antenna heights. */
data class LineOfSightInputs(
    val pointA: GeoCoordinate,
    val pointB: GeoCoordinate,
    val heightAMeters: Double,
    val heightBMeters: Double,
    val frequencyMHz: Double,
    val refractionK: Double,
)

/** Raw text fields from the LOS form (or tests). */
data class LineOfSightFormTexts(
    val latA: String,
    val lonA: String,
    val heightA: String,
    val latB: String,
    val lonB: String,
    val heightB: String,
    val frequencyMHz: String,
    val refractionK: String,
)

sealed class LineOfSightParseResult {
    data class Ok(val inputs: LineOfSightInputs) : LineOfSightParseResult()
    data object Invalid : LineOfSightParseResult()
}

fun parseLineOfSightInputs(form: LineOfSightFormTexts): LineOfSightParseResult {
    val pointA = losPoint(form.latA, form.lonA)
    val pointB = losPoint(form.latB, form.lonB)
    val radio = losRadio(form.frequencyMHz, form.refractionK)
    val heightA = form.heightA.trim().toDoubleOrNull() ?: 0.0
    val heightB = form.heightB.trim().toDoubleOrNull() ?: 0.0
    return if (pointA == null || pointB == null || radio == null) {
        LineOfSightParseResult.Invalid
    } else {
        LineOfSightParseResult.Ok(
            LineOfSightInputs(
                pointA = pointA,
                pointB = pointB,
                heightAMeters = heightA,
                heightBMeters = heightB,
                frequencyMHz = radio.frequencyMHz,
                refractionK = radio.refractionK,
            ),
        )
    }
}

private fun losPoint(latitudeText: String, longitudeText: String): GeoCoordinate? {
    val latitude = latitudeText.trim().toDoubleOrNull() ?: return null
    val longitude = longitudeText.trim().toDoubleOrNull() ?: return null
    if (latitude !in LAT_RANGE || longitude !in LON_RANGE) return null
    return GeoCoordinate(latitude, longitude)
}

private data class LosRadio(val frequencyMHz: Double, val refractionK: Double)

private fun losRadio(frequencyText: String, refractionText: String): LosRadio? {
    val frequencyMHz = frequencyText.trim().toDoubleOrNull() ?: return null
    val refractionK = refractionText.trim().toDoubleOrNull() ?: return null
    return if (frequencyMHz > 0.0 && refractionK > 0.0) LosRadio(frequencyMHz, refractionK) else null
}

/** Runs [RFCalculator.analyzePath] over a flat profile for the given inputs. */
fun analyzeLineOfSightFlat(inputs: LineOfSightInputs): PathAnalysisResult {
    val profile = flatElevationProfile(inputs.pointA, inputs.pointB)
    return analyzeLineOfSightWithProfile(inputs, profile)
}

/** Runs path analysis over an explicit elevation [profile] (terrain or flat). */
fun analyzeLineOfSightWithProfile(inputs: LineOfSightInputs, profile: List<ElevationSample>): PathAnalysisResult =
    RFCalculator.analyzePath(
        elevationProfile = profile,
        pointAHeightMeters = inputs.heightAMeters,
        pointBHeightMeters = inputs.heightBMeters,
        frequencyMHz = inputs.frequencyMHz,
        refractionK = inputs.refractionK,
    )

/**
 * Outcome of one LOS analyze pass: RF metrics plus the elevation series used for the terrain
 * chart, and whether real terrain data was used (vs sea-level approximation).
 */
data class LineOfSightRun(
    val analysis: PathAnalysisResult,
    val profile: List<ElevationSample>,
    val usedTerrainElevation: Boolean,
)

/**
 * Builds a sea-level profile and analysis. Prefer [runLineOfSightAnalysis] when an
 * [one.meshcore.shared.map.ElevationService] can supply terrain.
 */
fun runLineOfSightFlat(inputs: LineOfSightInputs): LineOfSightRun {
    val profile = flatElevationProfile(inputs.pointA, inputs.pointB)
    return LineOfSightRun(
        analysis = analyzeLineOfSightWithProfile(inputs, profile),
        profile = profile,
        usedTerrainElevation = false,
    )
}

/**
 * Maps map-layer elevation samples into RF [ElevationSample] rows (same coordinate/elevation
 * semantics; RF uses [GeoCoordinate]).
 */
fun terrainProfileFromMapSamples(samples: List<one.meshcore.shared.map.ElevationSample>): List<ElevationSample> =
    samples.map { sample ->
        ElevationSample(
            coordinate = GeoCoordinate(sample.coordinate.latitude, sample.coordinate.longitude),
            elevation = sample.elevation,
            distanceFromAMeters = sample.distanceFromAMeters,
        )
    }

private const val DEFAULT_FLAT_SAMPLE_COUNT = 64
private const val MIN_SAMPLE_COUNT = 2
private val LAT_RANGE = -90.0..90.0
private val LON_RANGE = -180.0..180.0
