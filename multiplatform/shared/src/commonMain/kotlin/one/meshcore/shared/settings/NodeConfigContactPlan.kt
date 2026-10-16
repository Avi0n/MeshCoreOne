package one.meshcore.shared.settings

import one.meshcore.shared.contact.utf8BytePrefix
import one.meshcore.shared.model.toLowercaseHex

/**
 * Validates and deduplicates contacts (newest `last_modified` wins per public key), enforces device
 * capacity counting only new keys, drops records the device already stores byte-for-byte, and rejects
 * invalid keys/paths/coordinates. Ports the Swift `planContactRecords`.
 */
internal fun planContactRecords(
    contacts: List<MeshCoreNodeConfig.ContactConfig>,
    maxContacts: Int,
    existingContacts: Map<String, ExistingContact>,
): List<PlannedContact> {
    val byKey = mutableMapOf<String, Pair<MeshCoreNodeConfig.ContactConfig, ByteArray>>()
    val order = mutableListOf<String>()

    for (contact in contacts) {
        val publicKey = hexToBytes(contact.publicKey)
        if (publicKey == null || publicKey.size != NodeConfigLimits.PUBLIC_KEY_SIZE) {
            throw NodeConfigError.InvalidContactPublicKey(contact.name)
        }
        val key = publicKey.toLowercaseHex()
        val existing = byKey[key]
        if (existing != null) {
            if (contact.lastModified >= existing.first.lastModified) byKey[key] = contact to publicKey
        } else {
            byKey[key] = contact to publicKey
            order += key
        }
    }

    val newKeyCount = order.count { it !in existingContacts.keys }
    val availableSlots = maxContacts - existingContacts.size
    if (newKeyCount > availableSlots) {
        throw NodeConfigError.ContactCapacityExceeded(newKeyCount, availableSlots)
    }

    return order.mapNotNull { key ->
        val (contactConfig, publicKey) = byKey.getValue(key)
        val record = buildContactRecord(contactConfig, publicKey, key)
        val existing = existingContacts[key]
        if (existing != null && persistedContactFieldsMatch(existing, record)) null else record
    }
}

private fun persistedContactFieldsMatch(existing: ExistingContact, record: PlannedContact): Boolean {
    val identityMatches = existing.typeRawValue == record.typeRawValue &&
        existing.flags == record.flags &&
        existing.lastModified == record.lastModified
    val pathMatches = existing.outPathLength == record.outPathLength &&
        existing.outPath.contentEquals(record.outPath)
    val nameMatches = existing.advertisedName.utf8BytePrefix(NodeConfigLimits.MAX_USABLE_NAME_BYTES) ==
        record.advertisedName.utf8BytePrefix(NodeConfigLimits.MAX_USABLE_NAME_BYTES)
    val positionMatches =
        NodeConfigLimits.scaledCoordinate(existing.latitude) == NodeConfigLimits.scaledCoordinate(record.latitude) &&
            NodeConfigLimits.scaledCoordinate(existing.longitude) ==
            NodeConfigLimits.scaledCoordinate(record.longitude)
    val fieldsMatch = identityMatches && pathMatches && nameMatches
    return fieldsMatch && positionMatches
}

private fun buildContactRecord(
    contact: MeshCoreNodeConfig.ContactConfig,
    publicKey: ByteArray,
    hexKey: String,
): PlannedContact {
    val (outPath, outPathLength) = resolveOutPath(contact)
    val lat = validatedCoordinate(contact.latitude, CoordinateField.ContactLatitude(contact.name), LATITUDE_LIMIT)
    val lon = validatedCoordinate(contact.longitude, CoordinateField.ContactLongitude(contact.name), LONGITUDE_LIMIT)
    return PlannedContact(
        id = hexKey,
        publicKey = publicKey,
        typeRawValue = contact.type,
        flags = contact.flags,
        outPathLength = outPathLength,
        outPath = outPath,
        advertisedName = contact.name,
        lastAdvert = contact.lastAdvert.toLong(),
        latitude = lat,
        longitude = lon,
        lastModified = contact.lastModified.toLong(),
    )
}

/** Resolves the encoded out-path, rejecting malformed routing rather than downgrading a routed contact. */
private fun resolveOutPath(contact: MeshCoreNodeConfig.ContactConfig): Pair<ByteArray, UByte> {
    val pathHex = contact.outPath ?: return ByteArray(0) to NodeConfigLimits.FLOOD_PATH_SENTINEL
    if (pathHex.isEmpty()) return ByteArray(0) to 0u

    val pathData = hexToBytes(pathHex)
    val mode = (contact.pathHashMode ?: 0u).toInt()
    val problem = outPathProblem(contact, pathData, mode)
    if (problem != null) throw problem
    val data = requireNotNull(pathData)
    val hashSize = mode + 1
    val hopCount = data.size / hashSize
    return data to NodeConfigLimits.encodePathLen(hashSize, hopCount)
}

private fun outPathProblem(
    contact: MeshCoreNodeConfig.ContactConfig,
    pathData: ByteArray?,
    mode: Int,
): NodeConfigError? {
    if (pathData == null || pathData.isEmpty()) return NodeConfigError.InvalidOutPath(contact.name)
    if (mode > NodeConfigLimits.MAX_PATH_HASH_MODE) {
        return NodeConfigError.InvalidPathHashMode(contact.name, mode)
    }
    val hashSize = mode + 1
    if (pathData.size % hashSize != 0 ||
        pathData.size / hashSize > NodeConfigLimits.MAX_HOP_COUNT ||
        pathData.size > NodeConfigLimits.MAX_PATH_BYTES
    ) {
        return NodeConfigError.InvalidOutPath(contact.name)
    }
    return null
}

private const val LATITUDE_LIMIT = NodeConfigLimits.LATITUDE_LIMIT
private const val LONGITUDE_LIMIT = NodeConfigLimits.LONGITUDE_LIMIT
