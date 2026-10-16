package one.meshcore.shared.simulator

import one.meshcore.shared.persistence.MeshDatabase

/**
 * Seeds Room with the iOS `SimulatorConnectionMode.seedDataStore` catalog. Re-seeding upserts
 * on each row id and keeps an existing contact avatar; RX-log and node-status snapshots skip
 * when already present so reconnect does not duplicate them.
 */
internal object SimulatorSeeder {
    suspend fun seed(database: MeshDatabase, nowEpochSeconds: Double) {
        val provider = MockDataProvider
        database.deviceDao().upsert(provider.simulatorDevice(nowEpochSeconds))
        seedContacts(database, nowEpochSeconds)

        database.channelDao().upsertAll(provider.channels(nowEpochSeconds))

        val messages = buildList {
            for (contact in provider.contacts(nowEpochSeconds)) {
                addAll(provider.messagesForContact(contact.id, nowEpochSeconds))
            }
            for (channel in provider.channels(nowEpochSeconds)) {
                addAll(provider.channelMessages(channel.index, nowEpochSeconds))
            }
        }
        database.messageDao().upsertAll(messages)

        for (seed in provider.linkPreviewSeeds()) {
            val row = database.messageDao().getById(seed.messageID) ?: continue
            database.messageDao().upsert(
                row.copy(
                    linkPreviewURL = seed.url,
                    linkPreviewTitle = seed.title,
                    linkPreviewImageData = seed.imageData,
                    linkPreviewFetched = true,
                ),
            )
        }

        for (reacted in provider.reactedMessages()) {
            database.reactionDao().upsertAll(provider.reactions(reacted.messageID, nowEpochSeconds))
            val row = database.messageDao().getById(reacted.messageID) ?: continue
            database.messageDao().upsert(row.copy(reactionSummary = reacted.summary))
        }

        for (messageID in provider.messagesWithRepeats()) {
            database.messageRepeatDao().upsertAll(provider.messageRepeats(messageID, nowEpochSeconds))
        }

        val existingRx = database.rxLogEntryDao().listNewestFirst(provider.simulatorDeviceID, limit = 64)
            .map { it.id }
            .toSet()
        for (entry in provider.rxLogEntries(nowEpochSeconds)) {
            if (entry.id !in existingRx) {
                database.rxLogEntryDao().upsert(entry)
            }
        }

        val nodeKey = provider.locationHistoryNodePublicKey()
        if (database.nodeStatusSnapshotDao().listByNode(nodeKey).isEmpty()) {
            database.nodeStatusSnapshotDao().upsertAll(provider.nodeStatusSnapshots(nowEpochSeconds))
        }
    }

    /** Re-seeding keeps an avatar the user already stored for that contact. */
    private suspend fun seedContacts(database: MeshDatabase, nowEpochSeconds: Double) {
        for (contact in MockDataProvider.contacts(nowEpochSeconds)) {
            val existing = database.contactDao().getById(contact.id)
            val withAvatar = if (existing?.avatarImageData != null) {
                contact.copy(avatarImageData = existing.avatarImageData)
            } else {
                contact
            }
            database.contactDao().upsert(withAvatar)
        }
    }
}
