package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/** A contact store that throws [deleteError] for [faultId] and otherwise forwards deletes to [inner]. */
internal class DeleteContactFaultStore(
    private val inner: InMemoryContactStore,
    private val faultId: Uuid,
    private val deleteError: Throwable,
) : ContactStore by inner {
    override suspend fun deleteContact(id: Uuid) {
        if (id == faultId) throw deleteError
        inner.deleteContact(id)
    }
}
