package one.meshcore.shared.navigation

/**
 * A request for the Map tab to drop a pin and center on a coordinate, set by a coordinate tap or a
 * `meshcore://map` deep link and consumed once by the Map section. Mirrors the iOS `MapFocusRequest`.
 */
data class MapFocusRequest(val latitude: Double, val longitude: Double)
