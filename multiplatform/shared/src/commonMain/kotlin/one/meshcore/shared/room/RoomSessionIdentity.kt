package one.meshcore.shared.room

import one.meshcore.shared.backup.RemoteNodeSessionDto

internal fun RemoteNodeSessionDto.hasSameRoomIdentity(other: RemoteNodeSessionDto): Boolean =
    id == other.id && radioID == other.radioID && publicKey.contentEquals(other.publicKey)
