package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * Process-lifetime channel sender tables so a list-tap or arrival prime can seed the next open's
 * first composition. Tests bind [isolatedStorage] so parallel suites do not share this map. Mirrors
 * the contact half of iOS `ChatTimelinePrimer.fetchSenderTables`.
 */
object ChannelSenderTableStore {
    class Storage {
        var tablesByRadioID: Map<Uuid, ChannelSenderTables> = emptyMap()
    }

    private val isolation = ProcessIsolation { Storage() }

    /** Test-only override. Production reads the process map. */
    internal var isolatedStorage: Storage?
        get() = isolation.isolatedStorage
        set(value) {
            isolation.isolatedStorage = value
        }

    private val storage: Storage
        get() = isolation.storage

    fun replace(radioID: Uuid, tables: ChannelSenderTables) {
        storage.tablesByRadioID = storage.tablesByRadioID + (radioID to tables)
    }

    fun tables(radioID: Uuid?): ChannelSenderTables {
        if (radioID == null) return ChannelSenderTables.EMPTY
        return storage.tablesByRadioID[radioID] ?: ChannelSenderTables.EMPTY
    }

    /** Runs [block] against a fresh map, then restores the previous isolation. */
    fun withIsolatedStorage(block: () -> Unit) = isolation.withIsolatedStorage(block)

    /** Suspend variant so a prime can run inside the isolated map. */
    suspend fun <T> withIsolatedStorageSuspending(block: suspend () -> T): T =
        isolation.withIsolatedStorageSuspending(block)
}
