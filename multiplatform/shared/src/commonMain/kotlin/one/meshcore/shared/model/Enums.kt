package one.meshcore.shared.model

import kotlinx.serialization.Serializable

// Raw values below are pinned to match the Swift enums byte-for-byte. They are the on-disk backup
// format, so a reorder or rename must never change the number that reaches the wire. Never rely on
// ordinal. The README's prose inverts NotificationLevel; the authoritative Swift source and the
// fixtures (notificationLevel 1 == mentionsOnly, missing == all) are followed here.

/** Notification level for conversations. Swift `NotificationLevel`, `Int`-raw. */
@Serializable(with = NotificationLevelSerializer::class)
enum class NotificationLevel(val raw: Int) {
    MUTED(0),
    MENTIONS_ONLY(1),
    ALL(2),
    ;

    companion object {
        fun fromRaw(raw: Int): NotificationLevel? = entries.firstOrNull { it.raw == raw }
    }
}

object NotificationLevelSerializer :
    IntEnumSerializer<NotificationLevel>("NotificationLevel", NotificationLevel::fromRaw, { it.raw })

/** Message direction. Swift `MessageDirection`, `Int`-raw. */
@Serializable(with = MessageDirectionSerializer::class)
enum class MessageDirection(val raw: Int) {
    INCOMING(0),
    OUTGOING(1),
    ;

    companion object {
        fun fromRaw(raw: Int): MessageDirection? = entries.firstOrNull { it.raw == raw }
    }
}

object MessageDirectionSerializer :
    IntEnumSerializer<MessageDirection>("MessageDirection", MessageDirection::fromRaw, { it.raw })

/** Message delivery status. Swift `MessageStatus`, `Int`-raw. */
@Serializable(with = MessageStatusSerializer::class)
enum class MessageStatus(val raw: Int) {
    PENDING(0),
    SENDING(1),
    SENT(2),
    DELIVERED(3),
    FAILED(4),
    RETRYING(5),
    ;

    companion object {
        fun fromRaw(raw: Int): MessageStatus? = entries.firstOrNull { it.raw == raw }
    }
}

object MessageStatusSerializer :
    IntEnumSerializer<MessageStatus>("MessageStatus", MessageStatus::fromRaw, { it.raw })

/** Message text type. Swift `TextType`, `UInt8`-raw (encoded as a JSON number). */
@Serializable(with = TextTypeSerializer::class)
enum class TextType(val raw: Int) {
    PLAIN(0x00),
    CLI_DATA(0x01),
    SIGNED_PLAIN(0x02),
    ;

    companion object {
        fun fromRaw(raw: Int): TextType? = entries.firstOrNull { it.raw == raw }
    }
}

object TextTypeSerializer :
    IntEnumSerializer<TextType>("TextType", TextType::fromRaw, { it.raw })

/** Remote node role. Swift `RemoteNodeRole`, `UInt8`-raw. */
@Serializable(with = RemoteNodeRoleSerializer::class)
enum class RemoteNodeRole(val raw: Int) {
    REPEATER(0x02),
    ROOM_SERVER(0x03),
    ;

    companion object {
        fun fromRaw(raw: Int): RemoteNodeRole? = entries.firstOrNull { it.raw == raw }
    }
}

object RemoteNodeRoleSerializer :
    IntEnumSerializer<RemoteNodeRole>("RemoteNodeRole", RemoteNodeRole::fromRaw, { it.raw })

/** Room permission level. Swift `RoomPermissionLevel`, `UInt8`-raw. */
@Serializable(with = RoomPermissionLevelSerializer::class)
enum class RoomPermissionLevel(val raw: Int) {
    GUEST(0x00),
    READ_WRITE(0x01),
    ADMIN(0x02),
    ;

    companion object {
        fun fromRaw(raw: Int): RoomPermissionLevel? = entries.firstOrNull { it.raw == raw }
    }
}

object RoomPermissionLevelSerializer :
    IntEnumSerializer<RoomPermissionLevel>("RoomPermissionLevel", RoomPermissionLevel::fromRaw, { it.raw })

/** Route type from RxLog correlation. Swift `RouteType`, `UInt8`-raw. Absent from the fixtures. */
@Serializable(with = RouteTypeSerializer::class)
enum class RouteType(val raw: Int) {
    TC_FLOOD(0),
    FLOOD(1),
    DIRECT(2),
    TC_DIRECT(3),
    ;

    companion object {
        fun fromRaw(raw: Int): RouteType? = entries.firstOrNull { it.raw == raw }
    }
}

object RouteTypeSerializer :
    IntEnumSerializer<RouteType>("RouteType", RouteType::fromRaw, { it.raw })

/** Contact type. Swift `ContactType`, `UInt8`-raw. Stored as `typeRawValue` in the contact entity. */
@Serializable(with = ContactTypeSerializer::class)
enum class ContactType(val raw: Int) {
    CHAT(0x01),
    REPEATER(0x02),
    ROOM(0x03),
    ;

    companion object {
        fun fromRaw(raw: Int): ContactType? = entries.firstOrNull { it.raw == raw }
    }
}

object ContactTypeSerializer :
    IntEnumSerializer<ContactType>("ContactType", ContactType::fromRaw, { it.raw })
