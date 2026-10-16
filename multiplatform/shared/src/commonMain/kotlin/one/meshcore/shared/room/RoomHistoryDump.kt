package one.meshcore.shared.room

/** iOS `RoomServerService.syncHistoryIfPossible` advert-path start dump. */
fun tryingAdvertPathForRoomLine(name: String): String = "Trying advert path for room $name"

/** iOS `RoomServerService.syncHistoryIfPossible` advert-path success dump. */
const val HISTORY_SYNC_SUCCEEDED_USING_ADVERT_PATH = "History sync succeeded using advert path"

/** iOS `RoomServerService.syncHistoryIfPossible` advert-path fail dump. */
fun advertPathFailedLine(name: String, detail: String): String =
    "Advert path failed for $name: $detail, trying path discovery"

/** iOS `RoomServerService.syncHistoryIfPossible` flood-routed dump. */
fun roomIsFloodRoutedLine(name: String): String = "Room $name is flood-routed, attempting path discovery"

/** iOS `RoomServerService.syncHistoryIfPossible` no-route dump. */
fun couldNotEstablishDirectRouteLine(name: String): String =
    "Could not establish direct route for $name, skipping history sync"

/** iOS `RoomServerService.syncHistoryIfPossible` discovery-retry success dump. */
const val HISTORY_SYNC_SUCCEEDED_AFTER_PATH_DISCOVERY = "History sync succeeded after path discovery"

/** iOS `RemoteNodeService.requestHistorySync` success dump. */
fun requestedHistorySyncForRoomLine(name: String): String = "Requested history sync for room $name"
