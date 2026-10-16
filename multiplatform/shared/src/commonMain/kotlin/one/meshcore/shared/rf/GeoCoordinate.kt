package one.meshcore.shared.rf

/**
 * A geographic coordinate in decimal degrees. The RF layer is deliberately self-contained and does
 * not depend on the map module's coordinate type, mirroring the Swift `RFCalculator` which took the
 * platform `CLLocationCoordinate2D` rather than an app-level model.
 */
data class GeoCoordinate(val latitude: Double, val longitude: Double)
