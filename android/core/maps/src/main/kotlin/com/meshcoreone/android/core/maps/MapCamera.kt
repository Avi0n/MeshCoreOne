// AndroidOnly: WP-312 camera restoration equivalent to MapCameraStore.swift.
package com.meshcoreone.android.core.maps

data class MapCamera(
    val center: GeoPoint,
    val latitudeSpan: Double,
    val longitudeSpan: Double,
    val pose: MapCameraPose? = null,
) {
    init {
        require(latitudeSpan.isFinite() && longitudeSpan.isFinite())
        require(latitudeSpan > 0.0 && longitudeSpan > 0.0)
    }

    fun encode(): String = "${center.latitude},${center.longitude},$latitudeSpan,$longitudeSpan" +
        (pose?.let { ",${it.zoom},${it.bearing},${it.tilt}" } ?: "")

    companion object {
        fun decode(value: String): MapCamera? {
            val parts = value.split(',')
            if (parts.size != 4 && parts.size != 7) return null
            val values = parts.map { it.toDoubleOrNull() ?: return null }
            return runCatching {
                MapCamera(
                    GeoPoint(values[0], values[1]), values[2], values[3],
                    if (values.size == 7) MapCameraPose(values[4], values[5], values[6]) else null,
                )
            }.getOrNull()
        }

    }
}

data class MapCameraPose(val zoom: Double, val bearing: Double, val tilt: Double) {
    init {
        require(zoom.isFinite() && zoom in 0.0..25.5)
        require(bearing.isFinite() && bearing in 0.0..360.0)
        require(tilt.isFinite() && tilt in 0.0..85.0)
    }
}
