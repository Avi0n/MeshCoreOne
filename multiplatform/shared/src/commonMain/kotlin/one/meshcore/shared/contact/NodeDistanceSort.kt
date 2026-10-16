package one.meshcore.shared.contact

/**
 * Located rows first, then nearest to [userLocation], then [byName]. With no [userLocation], only [byName]
 * applies. Distance is read only for a located row.
 */
internal fun <T> distanceThenNameComparator(
    userLocation: UserLocation?,
    hasLocation: (T) -> Boolean,
    distanceMeters: (T, UserLocation) -> Double,
    byName: Comparator<T>,
): Comparator<T> = Comparator { lhs, rhs ->
    if (userLocation != null) {
        val lhsLocated = hasLocation(lhs)
        val rhsLocated = hasLocation(rhs)
        if (lhsLocated != rhsLocated) return@Comparator if (lhsLocated) -1 else 1
        if (lhsLocated) {
            val distanceCompare = distanceMeters(lhs, userLocation).compareTo(distanceMeters(rhs, userLocation))
            if (distanceCompare != 0) return@Comparator distanceCompare
        }
    }
    byName.compare(lhs, rhs)
}

/**
 * Known hop counts ascending; a null count sorts last. Equal hops, including both unknown, use [fallback].
 */
internal fun <T> hopsThenComparator(hops: (T) -> Int?, fallback: Comparator<T>): Comparator<T> =
    Comparator { lhs, rhs ->
        val lhsHops = hops(lhs)
        val rhsHops = hops(rhs)
        if ((lhsHops == null) != (rhsHops == null)) return@Comparator if (lhsHops != null) -1 else 1
        if (lhsHops != null && rhsHops != null && lhsHops != rhsHops) return@Comparator lhsHops.compareTo(rhsHops)
        fallback.compare(lhs, rhs)
    }
