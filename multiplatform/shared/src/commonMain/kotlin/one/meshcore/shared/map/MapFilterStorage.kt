package one.meshcore.shared.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal fun encodeMapFilter(state: MapFilterState): String = buildJsonObject {
    put("favoritesOnly", state.favoritesOnly)
    put("showDiscovered", state.showDiscovered)
    put("showChat", state.showChat)
    put("showRepeater", state.showRepeater)
    put("showRoom", state.showRoom)
    put(
        "lastHeard",
        buildJsonObject {
            state.lastHeard.minAge?.let { put("minAge", it.toJson()) }
            state.lastHeard.maxAge?.let { put("maxAge", it.toJson()) }
        },
    )
    put(
        "hops",
        buildJsonObject {
            put("min", state.hops.min)
            state.hops.max?.let { put("max", it) }
        },
    )
}.toString()

internal fun decodeMapFilter(raw: String): MapFilterState? = runCatching {
    val root = requireNotNull(Json.parseToJsonElement(raw) as? JsonObject)
    MapFilterState(
        favoritesOnly = root.boolean("favoritesOnly", false),
        showDiscovered = root.boolean("showDiscovered", false),
        showChat = root.boolean("showChat", true),
        showRepeater = root.boolean("showRepeater", true),
        showRoom = root.boolean("showRoom", true),
        lastHeard = (root["lastHeard"] as? JsonObject)?.let {
            MapLastHeardRange(it["minAge"].duration(), it["maxAge"].duration())
        } ?: MapLastHeardRange.ANY,
        hops = (root["hops"] as? JsonObject)?.let {
            MapHopRange(
                min = it["min"].boundedInteger(MapHopRange.HOP_DOMAIN) ?: MapHopRange.HOP_DOMAIN.first,
                max = it["max"].boundedInteger(MapHopRange.HOP_DOMAIN),
            )
        } ?: MapHopRange.ANY,
    )
}.getOrNull()

private fun JsonObject.boolean(key: String, default: Boolean): Boolean {
    val element = this[key]
    if (element == null || element == JsonNull) return default
    val primitive = element as? JsonPrimitive
    require(primitive != null && !primitive.isString && primitive.booleanOrNull != null)
    return requireNotNull(primitive.booleanOrNull)
}

private fun JsonElement?.boundedInteger(range: IntRange): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val decimalInteger = primitive.content.takeIf {
        '.' in it && it.substringAfter('.').all { digit -> digit == '0' }
    }?.substringBefore('.')?.toLongOrNull()?.takeUnless { it == Long.MIN_VALUE || it == Long.MAX_VALUE }
    val integer = primitive.longOrNull ?: decimalInteger ?: primitive.doubleOrNull?.takeIf {
        it.isFinite() && it % 1.0 == 0.0 && it > Long.MIN_VALUE.toDouble() && it < Long.MAX_VALUE.toDouble()
    }?.toLong()
    return integer?.coerceIn(range.first.toLong(), range.last.toLong())?.toInt()
}

private fun JsonElement?.duration(): MapDuration? {
    val objectValue = this as? JsonObject ?: return null
    val value = objectValue["value"].boundedInteger(MapDuration.VALUE_RANGE) ?: return null
    val unitValue = objectValue["unit"] as? JsonPrimitive ?: return null
    if (!unitValue.isString) return null
    val unit = MapDurationUnit.entries.firstOrNull { it.raw == unitValue.content } ?: return null
    return MapDuration(value, unit)
}

private fun MapDuration.toJson(): JsonObject = buildJsonObject {
    put("value", value)
    put("unit", unit.raw)
}
