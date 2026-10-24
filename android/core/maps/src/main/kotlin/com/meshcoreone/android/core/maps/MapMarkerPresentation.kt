// PortedFrom: MC1/Views/Map/MC1MapView+Layers.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: text symbols retain endpoint, hop and badge meaning without restricted Apple sprites.
package com.meshcoreone.android.core.maps

val MapMarker.symbolText: String
    get() = when (style) {
        MapPinStyle.POINT_A -> "A"
        MapPinStyle.POINT_B -> "B"
        MapPinStyle.CROSSHAIR -> "+"
        MapPinStyle.OBSTRUCTION -> "!"
        MapPinStyle.REPEATER_HOP, MapPinStyle.REPEATER_RING_WHITE -> hopIndex?.toString() ?: "R"
        MapPinStyle.CONTACT_REPEATER, MapPinStyle.REPEATER,
        MapPinStyle.REPEATER_RING_BLUE, MapPinStyle.REPEATER_RING_GREEN -> "R"
        MapPinStyle.CONTACT_CHAT -> "C"
        MapPinStyle.CONTACT_ROOM -> "#"
        MapPinStyle.BADGE -> ""
        MapPinStyle.DROPPED_PIN -> "+"
        MapPinStyle.LOCATION_FIX -> ""
        MapPinStyle.LOCATION_FIX_LATEST -> "+"
    }

/** Name visibility never suppresses a trace badge or hop/endpoint label. */
val MapMarker.semanticText: String
    get() = listOfNotNull(
        label?.takeIf(String::isNotBlank),
        when (style) {
            MapPinStyle.POINT_A, MapPinStyle.POINT_B, MapPinStyle.REPEATER_HOP,
            MapPinStyle.REPEATER_RING_WHITE, MapPinStyle.CROSSHAIR, MapPinStyle.OBSTRUCTION -> symbolText
            else -> null
        },
        badgeText?.takeIf(String::isNotBlank),
    ).distinct().joinToString(", ")
