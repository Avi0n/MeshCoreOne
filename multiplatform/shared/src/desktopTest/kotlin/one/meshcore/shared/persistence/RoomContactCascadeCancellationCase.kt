package one.meshcore.shared.persistence

internal enum class RoomContactCascadeCancellationCase(val frame: String) {
    SINGLE("one.meshcore.shared.persistence.RoomContactStore.deleteContact"),
    COLLECTION("one.meshcore.shared.persistence.RoomContactStore.deleteContacts"),
    UNREFERENCED("one.meshcore.shared.persistence.RoomContactBookkeeping.deleteContactIfUnreferenced"),
    MESSAGES("one.meshcore.shared.persistence.RoomContactBookkeeping.deleteMessagesForContact"),
}
