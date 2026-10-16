package one.meshcore.shared.rf

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RF propagation calculator for line-of-sight analysis.
 *
 * Provides functions for calculating wavelength, Fresnel zones, earth bulge, path loss, and
 * diffraction loss for radio frequency propagation analysis. Ported verbatim from Swift
 * `RFCalculator`; the characterization vectors are asserted to exact-equality, so the operation
 * order and use of `Double` + `kotlin.math` here are load-bearing for numeric parity.
 */
object RFCalculator {
    // MARK: - Constants

    /** Speed of light in meters per second. */
    const val SPEED_OF_LIGHT: Double = 299_792_458.0

    /** Earth's radius in kilometers. */
    const val EARTH_RADIUS_KM: Double = 6371.0

    /** Minimum Fresnel zone clearance percentage for a "clear" path. */
    const val CLEAR_CLEARANCE_THRESHOLD: Double = 80.0

    /** Minimum Fresnel zone clearance percentage for a "marginal" path. */
    const val MARGINAL_CLEARANCE_THRESHOLD: Double = 60.0

    /** Fresnel-Kirchhoff parameter at or below which the path is fully clear (no knife-edge loss). */
    private const val DIFFRACTION_CLEAR_THRESHOLD_V: Double = -0.78

    /** Distance margin (meters) within which a sample counts as an endpoint and is skipped. */
    private const val ENDPOINT_SKIP_MARGIN_METERS: Double = 1.0

    private const val HZ_PER_MHZ: Double = 1_000_000.0
    private const val METERS_PER_KM: Double = 1000.0
    private const val DEGREES_PER_HALF_CIRCLE: Double = 180.0

    /** Free-space path loss and knife-edge diffraction are both `20*log10(...)` (a field-quantity ratio in dB). */
    private const val DECIBEL_FACTOR: Double = 20.0

    /** FSPL frequency/distance constant `20*log10(4*pi*1e6/c)`; the literal the vectors pin to. */
    private const val FSPL_DISTANCE_FREQUENCY_CONSTANT: Double = -27.55

    /** Fresnel clearance is expressed as a percentage of the first-zone radius. */
    private const val PERCENT_SCALE: Double = 100.0

    /** ITU-R P.526 single-equation knife-edge coefficients. */
    private const val KNIFE_EDGE_LOSS_OFFSET: Double = 6.9
    private const val KNIFE_EDGE_V_SHIFT: Double = 0.1

    // MARK: - Wavelength

    /** Wavelength in meters for a frequency in megahertz; 0 for a non-positive frequency. */
    fun wavelength(frequencyMHz: Double): Double {
        if (frequencyMHz <= 0) return 0.0
        val frequencyHz = frequencyMHz * HZ_PER_MHZ
        return SPEED_OF_LIGHT / frequencyHz
    }

    // MARK: - Fresnel Zone

    /**
     * First Fresnel zone radius in meters at a point split into two leg distances; 0 on any
     * non-positive input. For best reception at least 60% of the first zone should be clear.
     */
    fun fresnelRadius(frequencyMHz: Double, distanceToAMeters: Double, distanceToBMeters: Double): Double {
        if (frequencyMHz <= 0 || distanceToAMeters <= 0 || distanceToBMeters <= 0) return 0.0

        val lambda = wavelength(frequencyMHz)
        val totalDistance = distanceToAMeters + distanceToBMeters

        // First Fresnel zone radius: r = sqrt((lambda * d1 * d2) / (d1 + d2))
        return sqrt((lambda * distanceToAMeters * distanceToBMeters) / totalDistance)
    }

    // MARK: - Earth Bulge

    /**
     * Earth bulge (curvature correction) in meters at a point split into two leg distances; 0 on
     * any non-positive input including [refractionK]. Use 1.0 for no adjustment, 1.33 (4/3) for
     * standard atmosphere, or 4.0 for ducting.
     */
    fun earthBulge(distanceToAMeters: Double, distanceToBMeters: Double, refractionK: Double): Double {
        if (distanceToAMeters <= 0 || distanceToBMeters <= 0 || refractionK <= 0) return 0.0

        val earthRadiusMeters = EARTH_RADIUS_KM * METERS_PER_KM
        val effectiveEarthRadius = refractionK * earthRadiusMeters

        // Earth bulge: h = (d1 * d2) / (2 * Re_effective)
        return (distanceToAMeters * distanceToBMeters) / (2 * effectiveEarthRadius)
    }

    // MARK: - Path Loss

    /**
     * Free-space path loss in dB; 0 on any non-positive input. The constant `-27.55` equals
     * `20*log10(4*pi*1e6/c)` and is used as the exact literal that the vectors pin outputs to.
     */
    fun pathLoss(distanceMeters: Double, frequencyMHz: Double): Double {
        if (distanceMeters <= 0 || frequencyMHz <= 0) return 0.0

        val distanceComponent = DECIBEL_FACTOR * log10(distanceMeters)
        val frequencyComponent = DECIBEL_FACTOR * log10(frequencyMHz)

        return distanceComponent + frequencyComponent + FSPL_DISTANCE_FREQUENCY_CONSTANT
    }

    // MARK: - Diffraction Loss

    /**
     * Knife-edge diffraction loss in dB for an obstruction of [obstructionHeightMeters] above
     * line-of-sight (positive = blocked); 0 on any non-positive leg distance or frequency.
     */
    fun diffractionLoss(
        obstructionHeightMeters: Double,
        distanceToAMeters: Double,
        distanceToBMeters: Double,
        frequencyMHz: Double,
    ): Double {
        if (distanceToAMeters <= 0 || distanceToBMeters <= 0 || frequencyMHz <= 0) return 0.0

        val lambda = wavelength(frequencyMHz)
        val totalDistance = distanceToAMeters + distanceToBMeters

        // Fresnel-Kirchhoff diffraction parameter:
        // v = h * sqrt(2 * (d1 + d2) / (lambda * d1 * d2))
        val vParam = obstructionHeightMeters * sqrt(
            2 * totalDistance / (lambda * distanceToAMeters * distanceToBMeters),
        )

        return Analysis.diffractionLossFromV(vParam)
    }

    // MARK: - Distance Calculation

    /** Great-circle distance in meters between two coordinates (Haversine). */
    fun distance(from: GeoCoordinate, to: GeoCoordinate): Double {
        val earthRadiusMeters = EARTH_RADIUS_KM * METERS_PER_KM

        val lat1 = from.latitude * PI / DEGREES_PER_HALF_CIRCLE
        val lat2 = to.latitude * PI / DEGREES_PER_HALF_CIRCLE
        val deltaLat = (to.latitude - from.latitude) * PI / DEGREES_PER_HALF_CIRCLE
        val deltaLon = (to.longitude - from.longitude) * PI / DEGREES_PER_HALF_CIRCLE

        val haversineA = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        val angularDistance = 2 * atan2(sqrt(haversineA), sqrt(1 - haversineA))

        return earthRadiusMeters * angularDistance
    }

    // MARK: - Path Analysis

    /**
     * Analyze a full path for clearance and signal propagation. Full-path distances are measured
     * from A itself, so the origin is 0 even if the first sample carries a non-zero distance.
     */
    fun analyzePath(
        elevationProfile: List<ElevationSample>,
        pointAHeightMeters: Double,
        pointBHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): PathAnalysisResult = Analysis.analyze(
        PathAnalysisRequest(
            elevationProfile = elevationProfile,
            startHeightMeters = pointAHeightMeters,
            endHeightMeters = pointBHeightMeters,
            frequencyMHz = frequencyMHz,
            refractionK = refractionK,
            distanceOriginMeters = 0.0,
        ),
    )

    // MARK: - Segment Analysis

    /**
     * Analyze a segment of the path. The distance origin is the first sample's
     * `distanceFromAMeters`; recorded obstruction distances stay in absolute full-path coordinates.
     */
    fun analyzePathSegment(
        elevationProfile: List<ElevationSample>,
        startHeightMeters: Double,
        endHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): PathAnalysisResult = Analysis.analyze(
        PathAnalysisRequest(
            elevationProfile = elevationProfile,
            startHeightMeters = startHeightMeters,
            endHeightMeters = endHeightMeters,
            frequencyMHz = frequencyMHz,
            refractionK = refractionK,
            distanceOriginMeters = elevationProfile.firstOrNull()?.distanceFromAMeters ?: 0.0,
        ),
    )

    private data class PathAnalysisRequest(
        val elevationProfile: List<ElevationSample>,
        val startHeightMeters: Double,
        val endHeightMeters: Double,
        val frequencyMHz: Double,
        val refractionK: Double,
        val distanceOriginMeters: Double,
    )

    private data class ProfileSweep(
        val worstClearancePercent: Double,
        val peakDiffractionLoss: Double,
        val obstructionPoints: List<ObstructionPoint>,
    )

    private data class SampleReading(
        val clearancePercent: Double,
        val diffractionLoss: Double,
        val obstructionHeight: Double,
    )

    /**
     * Shared core for [analyzePath] and [analyzePathSegment]. [PathAnalysisRequest.distanceOriginMeters]
     * anchors the local distance axis: 0 for a full path, the first sample's `distanceFromAMeters` for a
     * segment. Recorded obstruction points always keep the sample's original `distanceFromAMeters`
     * so they stay in full-path coordinates for chart rendering.
     */
    private object Analysis {
        /**
         * Diffraction loss from the Fresnel-Kirchhoff v parameter using the ITU-R P.526 single-equation
         * knife-edge model. Kept as one expression to stay continuous and monotonic across the whole
         * obstruction range, avoiding the branch-join drift of piecewise polynomial approximations.
         */
        fun diffractionLossFromV(vParam: Double): Double {
            if (vParam <= RFCalculator.DIFFRACTION_CLEAR_THRESHOLD_V) return 0.0
            val shifted = vParam - RFCalculator.KNIFE_EDGE_V_SHIFT
            return RFCalculator.KNIFE_EDGE_LOSS_OFFSET +
                RFCalculator.DECIBEL_FACTOR * log10(sqrt(shifted * shifted + 1) + shifted)
        }

        fun analyze(request: PathAnalysisRequest): PathAnalysisResult {
            if (request.elevationProfile.size < 2) {
                return emptyResult(request.frequencyMHz, request.refractionK)
            }
            val elevationProfile = request.elevationProfile
            val firstSample = elevationProfile.first()
            val lastSample = elevationProfile.last()
            val lengthMeters = lastSample.distanceFromAMeters - request.distanceOriginMeters
            if (lengthMeters <= 0) {
                return emptyResult(request.frequencyMHz, request.refractionK)
            }
            val antennaStartHeight = firstSample.elevation + request.startHeightMeters
            val antennaEndHeight = lastSample.elevation + request.endHeightMeters
            val fspl = RFCalculator.pathLoss(lengthMeters, request.frequencyMHz)
            val sweep = sweepProfile(request, lengthMeters, antennaStartHeight, antennaEndHeight)
            val worstClearancePercent = if (sweep.worstClearancePercent == Double.POSITIVE_INFINITY) {
                RFCalculator.PERCENT_SCALE
            } else {
                sweep.worstClearancePercent
            }
            return PathAnalysisResult(
                distanceMeters = lengthMeters,
                freeSpacePathLoss = fspl,
                peakDiffractionLoss = sweep.peakDiffractionLoss,
                totalPathLoss = fspl + sweep.peakDiffractionLoss,
                clearanceStatus = clearanceStatus(worstClearancePercent),
                worstClearancePercent = worstClearancePercent,
                obstructionPoints = sweep.obstructionPoints,
                frequencyMHz = request.frequencyMHz,
                refractionK = request.refractionK,
            )
        }

        private fun sweepProfile(
            request: PathAnalysisRequest,
            lengthMeters: Double,
            antennaStartHeight: Double,
            antennaEndHeight: Double,
        ): ProfileSweep {
            var worstClearancePercent = Double.POSITIVE_INFINITY
            var peakDiffractionLoss = 0.0
            val obstructionPoints = mutableListOf<ObstructionPoint>()
            for (sample in request.elevationProfile) {
                val reading = sampleReading(
                    sample,
                    request,
                    lengthMeters,
                    antennaStartHeight,
                    antennaEndHeight,
                ) ?: continue
                if (reading.clearancePercent < worstClearancePercent) {
                    worstClearancePercent = reading.clearancePercent
                }
                if (reading.diffractionLoss > peakDiffractionLoss) {
                    peakDiffractionLoss = reading.diffractionLoss
                }
                if (reading.clearancePercent < RFCalculator.MARGINAL_CLEARANCE_THRESHOLD) {
                    obstructionPoints.add(
                        ObstructionPoint(
                            distanceFromAMeters = sample.distanceFromAMeters,
                            obstructionHeightMeters = reading.obstructionHeight,
                            fresnelClearancePercent = reading.clearancePercent,
                        ),
                    )
                }
            }
            return ProfileSweep(
                worstClearancePercent = worstClearancePercent,
                peakDiffractionLoss = peakDiffractionLoss,
                obstructionPoints = obstructionPoints,
            )
        }

        // Endpoints are not terrain samples. A zero Fresnel radius has no zone to clear.
        private fun sampleReading(
            sample: ElevationSample,
            request: PathAnalysisRequest,
            lengthMeters: Double,
            antennaStartHeight: Double,
            antennaEndHeight: Double,
        ): SampleReading? {
            val distanceFromStart = sample.distanceFromAMeters - request.distanceOriginMeters
            val distanceToEnd = lengthMeters - distanceFromStart
            if (
                distanceFromStart <= RFCalculator.ENDPOINT_SKIP_MARGIN_METERS ||
                distanceToEnd <= RFCalculator.ENDPOINT_SKIP_MARGIN_METERS
            ) {
                return null
            }
            val fraction = distanceFromStart / lengthMeters
            val losHeight = antennaStartHeight + fraction * (antennaEndHeight - antennaStartHeight)
            val bulge = RFCalculator.earthBulge(distanceFromStart, distanceToEnd, request.refractionK)
            val effectiveTerrainHeight = sample.elevation + bulge
            val fresnelZoneRadius = RFCalculator.fresnelRadius(request.frequencyMHz, distanceFromStart, distanceToEnd)
            val clearance = losHeight - effectiveTerrainHeight
            val clearancePercent = if (fresnelZoneRadius > 0) {
                (clearance / fresnelZoneRadius) * RFCalculator.PERCENT_SCALE
            } else if (clearance > 0) {
                RFCalculator.PERCENT_SCALE
            } else {
                0.0
            }
            val obstructionHeight = effectiveTerrainHeight - losHeight
            val diffractionLoss = if (obstructionHeight > -fresnelZoneRadius) {
                RFCalculator.diffractionLoss(obstructionHeight, distanceFromStart, distanceToEnd, request.frequencyMHz)
            } else {
                0.0
            }
            return SampleReading(
                clearancePercent = clearancePercent,
                diffractionLoss = diffractionLoss,
                obstructionHeight = obstructionHeight,
            )
        }

        /** The zeroed blocked result returned for degenerate profiles. */
        private fun emptyResult(frequencyMHz: Double, refractionK: Double): PathAnalysisResult = PathAnalysisResult(
            distanceMeters = 0.0,
            freeSpacePathLoss = 0.0,
            peakDiffractionLoss = 0.0,
            totalPathLoss = 0.0,
            clearanceStatus = ClearanceStatus.Blocked,
            worstClearancePercent = 0.0,
            obstructionPoints = emptyList(),
            frequencyMHz = frequencyMHz,
            refractionK = refractionK,
        )

        /** Maps a worst-case Fresnel clearance percentage to a clearance status. */
        private fun clearanceStatus(percent: Double): ClearanceStatus = when {
            percent >= RFCalculator.CLEAR_CLEARANCE_THRESHOLD -> ClearanceStatus.Clear
            percent >= RFCalculator.MARGINAL_CLEARANCE_THRESHOLD -> ClearanceStatus.Marginal
            percent >= 0 -> ClearanceStatus.PartialObstruction
            else -> ClearanceStatus.Blocked
        }
    }
}
