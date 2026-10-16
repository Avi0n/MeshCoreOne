package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MetricSnrBadgeFormatter
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.map.SnrBadgeFormatter
import one.meshcore.shared.map.boundingRegion
import one.meshcore.shared.map.lineStyleForSnr
import one.meshcore.shared.map.snrBadge
import one.meshcore.shared.meshcore.Neighbour
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.remotenode.ui.NeighborPrefixResolver

/**
 * Pure builder for the neighbor SNR map (pins, SNR-colored spokes, midpoint badges). Mirrors iOS
 * `NeighborSNRMapBuilder`: only an exact match with a trustworthy coordinate is plotted; everything else
 * is returned as [UnplottableNeighbor] for the list card. Names and coordinates come from
 * [NeighborPrefixResolver], so a neighbour known only from an advert plots from its discovered row.
 */
object NeighborSnrMapBuilder {
    data class Plot(
        val points: List<MapPoint>,
        val lines: List<MapLine>,
        val region: MapCameraRegion?,
        val unplottable: List<UnplottableNeighbor>,
    )

    data class UnplottableNeighbor(
        val neighbor: Neighbour,
        val displayName: String,
        val reason: UnplottableReason,
        val matchKind: NodeNameMatchKind,
    )

    enum class UnplottableReason {
        UNRESOLVED,
        AMBIGUOUS,
        NO_LOCATION,
    }

    private const val LINE_OPACITY = 1.0

    /** Optional plot inputs. The session, neighbors, and contacts stay on [build]. */
    data class Options(
        val discovered: List<DiscoveredNodeRecord> = emptyList(),
        val userLocation: UserLocation? = null,
        val filter: MapFilterState = MapFilterState(),
        val formatter: SnrBadgeFormatter = MetricSnrBadgeFormatter(),
        val keyDisplayByteCount: Int = NeighborPrefixResolver.MIN_KEY_DISPLAY_BYTE_COUNT,
    )

    fun build(
        session: RemoteNodeSessionDto,
        neighbors: List<Neighbour>,
        contacts: List<ContactDto>,
        options: Options = Options(),
    ): Plot {
        val sanitized = options.filter.sanitized(MapFilterHost.NEIGHBOR_SNR)
        val effectiveContacts = if (sanitized.favoritesOnly) {
            contacts.filter { it.isFavorite }
        } else {
            contacts
        }
        val effectiveDiscovered = if (sanitized.effectiveShowDiscovered) options.discovered else emptyList()
        val draft = PlotDraft()
        val center = session.coordinate?.let { LatLng(it.latitude, it.longitude) }
        if (center != null) {
            draft.points += MapPoint(
                id = "neighbor-center-${session.id.value}",
                coordinate = center,
                pinStyle = PinStyle.REPEATER_RING_WHITE,
                label = session.name,
                isClusterable = true,
            )
            draft.plotted += center
        }
        neighbors.forEachIndexed { index, neighbor ->
            val resolved = NeighborPrefixResolver.resolveLocated(
                prefix = neighbor.publicKeyPrefix,
                contacts = effectiveContacts,
                discovered = effectiveDiscovered,
                userLocation = options.userLocation,
            )
            draft.addNeighbor(
                NeighborPlot(
                    index = index,
                    neighbor = neighbor,
                    resolved = resolved,
                    center = center,
                    formatter = options.formatter,
                    keyDisplayByteCount = options.keyDisplayByteCount,
                ),
            )
        }
        return draft.toPlot()
    }

    private data class NeighborPlot(
        val index: Int,
        val neighbor: Neighbour,
        val resolved: NeighborPrefixResolver.ResolvedNeighbor?,
        val center: LatLng?,
        val formatter: SnrBadgeFormatter,
        val keyDisplayByteCount: Int,
    )

    private class PlotDraft {
        val points = mutableListOf<MapPoint>()
        val lines = mutableListOf<MapLine>()
        val unplottable = mutableListOf<UnplottableNeighbor>()
        val plotted = mutableListOf<LatLng>()

        fun addNeighbor(plot: NeighborPlot) {
            val resolved = plot.resolved
            if (resolved == null) {
                unplottable += UnplottableNeighbor(
                    neighbor = plot.neighbor,
                    displayName = NeighborPrefixResolver.fallbackName(
                        plot.neighbor.publicKeyPrefix,
                        plot.keyDisplayByteCount,
                    ),
                    reason = UnplottableReason.UNRESOLVED,
                    matchKind = NodeNameMatchKind.UNRESOLVED,
                )
                return
            }
            if (resolved.matchKind != NodeNameMatchKind.EXACT) {
                unplottable += UnplottableNeighbor(
                    neighbor = plot.neighbor,
                    displayName = resolved.displayName,
                    reason = UnplottableReason.AMBIGUOUS,
                    matchKind = resolved.matchKind,
                )
                return
            }
            addExact(plot.index, plot.neighbor, resolved, plot.center, plot.formatter)
        }

        private fun addExact(
            index: Int,
            neighbor: Neighbour,
            resolved: NeighborPrefixResolver.ResolvedNeighbor,
            center: LatLng?,
            formatter: SnrBadgeFormatter,
        ) {
            val latitude = resolved.latitude
            val longitude = resolved.longitude
            if (latitude == null || longitude == null) {
                unplottable += UnplottableNeighbor(
                    neighbor = neighbor,
                    displayName = resolved.displayName,
                    reason = UnplottableReason.NO_LOCATION,
                    matchKind = resolved.matchKind,
                )
                return
            }
            val coordinate = LatLng(latitude, longitude)
            points += MapPoint(
                id = "neighbor-pin-$index",
                coordinate = coordinate,
                pinStyle = PinStyle.REPEATER,
                label = resolved.displayName,
                isClusterable = true,
            )
            plotted += coordinate
            if (center == null) return
            lines += MapLine(
                id = "neighbor-line-$index",
                coordinates = listOf(center, coordinate),
                style = lineStyleForSnr(neighbor.snr),
                opacity = LINE_OPACITY,
                pathIndex = index,
            )
            points += snrBadge(
                id = "neighbor-badge-$index",
                from = center,
                to = coordinate,
                snr = neighbor.snr,
                formatter = formatter,
            )
        }

        fun toPlot(): Plot = Plot(
            points = points,
            lines = lines,
            region = plotted.boundingRegion(),
            unplottable = disambiguatingUnresolved(unplottable),
        )
    }

    /**
     * Distinct unresolved neighbours can share the clamped key prefix; colliding titles
     * widen to the full stored prefix. Mirrors iOS `disambiguatingUnresolved`.
     */
    private fun disambiguatingUnresolved(unplottable: List<UnplottableNeighbor>): List<UnplottableNeighbor> {
        val titleCounts = mutableMapOf<String, Int>()
        for (item in unplottable) {
            if (item.matchKind == NodeNameMatchKind.UNRESOLVED) {
                titleCounts[item.displayName] = (titleCounts[item.displayName] ?: 0) + 1
            }
        }
        if (titleCounts.values.none { it > 1 }) return unplottable
        return unplottable.map { item ->
            if (item.matchKind != NodeNameMatchKind.UNRESOLVED) return@map item
            if ((titleCounts[item.displayName] ?: 0) <= 1) return@map item
            item.copy(displayName = item.neighbor.publicKeyPrefix.toUppercaseHex())
        }
    }
}
