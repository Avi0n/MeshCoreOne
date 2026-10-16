package one.meshcore.shared.map

enum class MapDurationUnit(val raw: String, val seconds: Long) {
    MINUTES("minutes", 60L),
    HOURS("hours", 3_600L),
    DAYS("days", 86_400L),
}

class MapDuration(value: Int, val unit: MapDurationUnit) {
    val value: Int = value.coerceIn(VALUE_RANGE)
    val seconds: Long get() = value * unit.seconds

    override fun equals(other: Any?): Boolean = other is MapDuration && value == other.value && unit == other.unit
    override fun hashCode(): Int = value.hashCode() * RANGE_HASH_MULTIPLIER + unit.hashCode()

    companion object {
        val VALUE_RANGE: IntRange = 1..9_999
    }
}

/** Inclusive rolling ages; unknown timestamps fail an active range and future stamps count as age zero. */
class MapLastHeardRange(minAge: MapDuration? = null, maxAge: MapDuration? = null) {
    val minAge: MapDuration? = minAge
    val maxAge: MapDuration? = if (minAge != null &&
        maxAge != null &&
        minAge.seconds > maxAge.seconds
    ) {
        minAge
    } else {
        maxAge
    }
    val isActive: Boolean get() = minAge != null || maxAge != null

    fun contains(heardAt: Long?, now: Long): Boolean {
        if (!isActive) return true
        if (heardAt == null) return false
        val age = (now.toDouble() - heardAt.toDouble()).coerceAtLeast(0.0)
        return (minAge == null || age >= minAge.seconds) && (maxAge == null || age <= maxAge.seconds)
    }

    fun withMinAge(value: MapDuration?): MapLastHeardRange = MapLastHeardRange(value, maxAge)

    fun withMaxAge(value: MapDuration?): MapLastHeardRange {
        val minimum = if (value != null && minAge != null && minAge.seconds > value.seconds) value else minAge
        return MapLastHeardRange(minimum, value)
    }

    override fun equals(other: Any?): Boolean =
        other is MapLastHeardRange && minAge == other.minAge && maxAge == other.maxAge

    override fun hashCode(): Int = minAge.hashCode() * RANGE_HASH_MULTIPLIER + maxAge.hashCode()

    companion object {
        val ANY = MapLastHeardRange()
        val PRESETS = listOf(
            ANY,
            within(MapDuration(15, MapDurationUnit.MINUTES)),
            within(MapDuration(1, MapDurationUnit.HOURS)),
            within(MapDuration(2, MapDurationUnit.HOURS)),
            within(MapDuration(6, MapDurationUnit.HOURS)),
            within(MapDuration(24, MapDurationUnit.HOURS)),
            within(MapDuration(3, MapDurationUnit.DAYS)),
            within(MapDuration(7, MapDurationUnit.DAYS)),
        )

        fun within(duration: MapDuration): MapLastHeardRange = MapLastHeardRange(maxAge = duration)
    }
}

/** Inclusive hop bounds. The protocol ceiling is equivalent to an absent maximum. */
class MapHopRange(min: Int = HOP_DOMAIN.first, max: Int? = null) {
    val min: Int = min.coerceIn(HOP_DOMAIN)
    val max: Int? = max?.coerceIn(HOP_DOMAIN)?.coerceAtLeast(this.min)?.takeUnless { it == HOP_DOMAIN.last }
    val isActive: Boolean get() = min > HOP_DOMAIN.first || max != null

    fun contains(hops: Int?): Boolean {
        if (!isActive) return true
        if (hops == null) return false
        return hops >= min && (max == null || hops <= max)
    }

    fun withMin(value: Int): MapHopRange = MapHopRange(value, max)

    fun withMax(value: Int?): MapHopRange {
        val maximum = value?.coerceIn(HOP_DOMAIN)
        return MapHopRange(if (maximum != null && min > maximum) maximum else min, maximum)
    }

    override fun equals(other: Any?): Boolean = other is MapHopRange && min == other.min && max == other.max
    override fun hashCode(): Int = min.hashCode() * RANGE_HASH_MULTIPLIER + max.hashCode()

    companion object {
        val HOP_DOMAIN: IntRange = 0..63
        val ANY = MapHopRange()
        val DIRECT = MapHopRange(max = HOP_DOMAIN.first)
        val PRESETS = listOf(ANY, DIRECT, MapHopRange(max = 1), MapHopRange(max = 2), MapHopRange(max = 3))
    }
}

enum class MapAdvancedFilterDimension {
    LAST_HEARD,
    HOPS,
}

private const val RANGE_HASH_MULTIPLIER = 31
