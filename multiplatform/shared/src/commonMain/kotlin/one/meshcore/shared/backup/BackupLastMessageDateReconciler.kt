package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase

/**
 * Advances `lastMessageDate` on the contacts, channels and remote-node sessions this import wrote
 * history into, mirroring iOS `reconcileLastMessageDates`. The conversation list sorts on that column
 * and hides a row whose value is null, so a restored conversation would otherwise sort by a stale date
 * or stay invisible until the next live message arrives. An existing newer value always wins, which is
 * also what makes envelope duplicates harmless: their timestamps are already reflected in the row.
 */
internal object BackupLastMessageDateReconciler {
    /**
     * [messages] is the post-mapping list, so a message whose channel was dropped is already gone and
     * cannot advance a slot it never landed in. [affectedSessionIDs] are the sessions that gained a
     * room message, bounding the room-message scan the way iOS does.
     */
    suspend fun reconcile(
        database: MeshDatabase,
        messages: List<MessageDto>,
        roomMessages: List<RoomMessageDto>,
        affectedSessionIDs: Set<Uuid>,
    ) {
        applyToContacts(database, contactMaxDates(messages))
        applyToChannels(database, channelMaxDates(messages))
        applyToSessions(database, sessionMaxDates(roomMessages, affectedSessionIDs))
    }

    private fun contactMaxDates(messages: List<MessageDto>): Map<Uuid, Double> {
        val maxDates = mutableMapOf<Uuid, Double>()
        for (message in messages) {
            val contactID = message.contactID ?: continue
            maxDates.merge(contactID, message.createdAt, ::maxOf)
        }
        return maxDates
    }

    private fun channelMaxDates(messages: List<MessageDto>): Map<Uuid, Map<Int, Double>> {
        val maxDates = mutableMapOf<Uuid, MutableMap<Int, Double>>()
        for (message in messages) {
            val index = message.channelIndex ?: continue
            maxDates.getOrPut(message.radioID) { mutableMapOf() }.merge(index, message.createdAt, ::maxOf)
        }
        return maxDates
    }

    private fun sessionMaxDates(roomMessages: List<RoomMessageDto>, affectedSessionIDs: Set<Uuid>): Map<Uuid, Double> {
        if (affectedSessionIDs.isEmpty()) return emptyMap()
        val maxDates = mutableMapOf<Uuid, Double>()
        for (roomMessage in roomMessages) {
            if (roomMessage.sessionID !in affectedSessionIDs) continue
            maxDates.merge(roomMessage.sessionID, roomMessage.createdAt, ::maxOf)
        }
        return maxDates
    }

    private suspend fun applyToContacts(database: MeshDatabase, maxDates: Map<Uuid, Double>) {
        for ((contactID, latest) in maxDates) {
            val row = database.contactDao().getById(contactID)
            if (row != null && isOlder(row.lastMessageDate, latest)) {
                database.contactDao().upsert(row.copy(lastMessageDate = latest))
            }
        }
    }

    private suspend fun applyToChannels(database: MeshDatabase, maxDates: Map<Uuid, Map<Int, Double>>) {
        for ((radioID, datesByIndex) in maxDates) {
            for (row in database.channelDao().listByRadio(radioID)) {
                val latest = datesByIndex[row.index]
                if (latest != null && isOlder(row.lastMessageDate, latest)) {
                    database.channelDao().upsert(row.copy(lastMessageDate = latest))
                }
            }
        }
    }

    private suspend fun applyToSessions(database: MeshDatabase, maxDates: Map<Uuid, Double>) {
        for ((sessionID, latest) in maxDates) {
            val row = database.remoteNodeSessionDao().getById(sessionID)
            if (row != null && isOlder(row.lastMessageDate, latest)) {
                database.remoteNodeSessionDao().upsert(row.copy(lastMessageDate = latest))
            }
        }
    }

    /** A row that has never carried a date is always older, which is what rescues it from the filter. */
    private fun isOlder(stored: Double?, latest: Double): Boolean = stored == null || stored < latest
}
