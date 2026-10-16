package one.meshcore.shared.backup

import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.contact.MAX_DISCOVERED_NODES_PER_RADIO
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.contact.utf8BytePrefix
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.toEntity
import one.meshcore.shared.remotenode.NodeLocationFix

/**
 * Imports the passive Discover list from a backup envelope, mirroring iOS
 * `batchInsertDiscoveredNodes`: sanitize each row to protocol bounds, drop rows past the per-radio cap
 * (oldest-by-`lastHeard` first, counting rows already committed), then write-if-missing on
 * `(radioID, publicKey)` with a fresh surrogate id (no inbound foreign keys depend on it).
 */
object DiscoveredNodeBackupImporter {
    /** Counts from one Discover-list import pass. */
    data class Outcome(val inserted: Int, val skipped: Int, val dropped: Int)

    suspend fun import(database: MeshDatabase, nodes: List<DiscoveredNodeDto>): Outcome {
        if (nodes.isEmpty()) return Outcome(0, 0, 0)

        var invalidSkipped = 0
        val valid = ArrayList<DiscoveredNodeDto>(nodes.size)
        for (dto in nodes) {
            val sanitized = sanitize(dto)
            if (sanitized == null) invalidSkipped++ else valid.add(sanitized)
        }

        val dao = database.discoveredNodeDao()
        val radioIDs = valid.map { it.radioID }.distinct()
        val existingKeys =
            radioIDs.flatMap { dao.listByRadio(it) }
                .mapTo(mutableSetOf()) { BackupMergeKeys.discoveredNodeKey(it.radioID, it.publicKey) }

        val (trimmed, dropped) = trimToCap(database, valid, existingKeys)

        val knownKeys = existingKeys.toMutableSet()
        var inserted = 0
        var skipped = invalidSkipped
        for (dto in trimmed) {
            val key = BackupMergeKeys.discoveredNodeKey(dto.radioID, dto.publicKey)
            if (!knownKeys.add(key)) {
                skipped++
                continue
            }
            dao.upsert(dto.copy(id = newLocalId()).toEntity())
            inserted++
        }
        return Outcome(inserted, skipped, dropped)
    }

    /**
     * Rejects rows whose public key is not protocol-sized; truncates name and out-path to protocol
     * bounds so a crafted envelope cannot bloat the store under the row-count cap. An out-of-range
     * lat/lon becomes (0,0) so restored rows are not plottable on the map.
     */
    private fun sanitize(dto: DiscoveredNodeDto): DiscoveredNodeDto? {
        if (dto.publicKey.size != ChatConstants.PUBLIC_KEY_SIZE_BYTES) return null
        val name = dto.name.utf8BytePrefix(MAX_USABLE_NAME_BYTES)
        val outPath =
            if (dto.outPath.size > PathEncoding.MAX_PATH_BYTES) {
                dto.outPath.copyOf(PathEncoding.MAX_PATH_BYTES)
            } else {
                dto.outPath
            }
        val plottable = NodeLocationFix.isValidFix(dto.latitude, dto.longitude)
        val latitude = if (plottable) dto.latitude else 0.0
        val longitude = if (plottable) dto.longitude else 0.0
        val metaUnchanged = name == dto.name && outPath.contentEquals(dto.outPath)
        val coordsUnchanged = latitude == dto.latitude && longitude == dto.longitude
        if (metaUnchanged && coordsUnchanged) return dto
        return dto.copy(name = name, outPath = outPath, latitude = latitude, longitude = longitude)
    }

    /**
     * Drops oldest-by-`lastHeard` incoming nodes past the per-radio cap, counting committed rows so a
     * full store admits no more. Local committed rows always win. Duplicate keys may over-drop (safe:
     * the cap is never exceeded).
     */
    private suspend fun trimToCap(
        database: MeshDatabase,
        dtos: List<DiscoveredNodeDto>,
        existingKeys: Set<String>,
    ): Pair<List<DiscoveredNodeDto>, Int> {
        val newByRadio = LinkedHashMap<Uuid, MutableList<DiscoveredNodeDto>>()
        for (dto in dtos) {
            if (BackupMergeKeys.discoveredNodeKey(dto.radioID, dto.publicKey) in existingKeys) continue
            newByRadio.getOrPut(dto.radioID) { mutableListOf() }.add(dto)
        }
        val dropped = mutableSetOf<Uuid>()
        val dao = database.discoveredNodeDao()
        for ((radioID, incoming) in newByRadio) {
            val committed = dao.countByRadio(radioID)
            val room = (MAX_DISCOVERED_NODES_PER_RADIO - committed).coerceAtLeast(0)
            if (incoming.size <= room) continue
            incoming.sortedByDescending { it.lastHeard }.drop(room).forEach { dropped.add(it.id) }
        }
        if (dropped.isEmpty()) return dtos to 0
        return dtos.filter { it.id !in dropped } to dropped.size
    }
}
