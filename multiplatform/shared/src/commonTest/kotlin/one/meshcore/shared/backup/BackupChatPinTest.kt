package one.meshcore.shared.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupChatPinTest {
    @Test
    fun missingAndNullPinsInheritLegacyFavorites() {
        families.forEach { family ->
            listOf(null, JsonNull).forEach { pin ->
                assertTrue(decodeRow(family, pin, JsonPrimitive(true)).pin(family))
                assertFalse(decodeRow(family, pin, JsonPrimitive(false)).pin(family))
            }
        }
    }

    @Test
    fun explicitPinsTakePrecedenceAndRoundTrip() {
        families.forEach { family ->
            val unpinned = decodeRow(family, JsonPrimitive(false), JsonPrimitive(true))
            assertFalse(unpinned.pin(family))
            assertFalse(decodeBackupEnvelope(encodeBackupEnvelope(unpinned)).pin(family))
            val pinned = decodeRow(family, JsonPrimitive(true), JsonPrimitive(false))
            assertTrue(decodeBackupEnvelope(encodeBackupEnvelope(pinned)).pin(family))
        }
    }

    @Test
    fun channelAndRoomMissingOrNullLegacyFavoritesDefaultToFalse() {
        families.filterNot { it == "contacts" }.forEach { family ->
            listOf(null, JsonNull).forEach { favorite ->
                assertFalse(decodeRow(family, null, favorite).pin(family))
            }
        }
    }

    @Test
    fun malformedLegacyFlagsFailInsteadOfLosingThePin() {
        families.forEach { family ->
            invalidFlags.forEach { flag ->
                assertFailsWith<AppBackupError.InvalidFile> { decodeRow(family, null, flag) }
                assertFailsWith<AppBackupError.InvalidFile> { decodeRow(family, flag, JsonPrimitive(true)) }
            }
        }
    }

    @Test
    fun mergeKeepsPinsAndRadioFavoritesIndependent() {
        val source = decodeBackupEnvelope(loadBackupFixture("full-envelope.json"))
        val local = source.contacts.single().copy(isFavorite = true, isPinned = false)
        val foreign = local.copy(isFavorite = false, isPinned = true)
        val adopted = BackupMerge.mergeContactMetadata(local, foreign).value
        assertTrue(adopted.isPinned)
        assertTrue(adopted.isFavorite)
        assertEquals(adopted, BackupMerge.mergeContactMetadata(adopted, local).value)
        val channel = source.channels.single().copy(isPinned = true)
        assertTrue(BackupMerge.mergeChannelMetadata(channel, channel.copy(isPinned = false)).value.isPinned)
        val room = source.remoteNodeSessions.single().copy(isPinned = true)
        assertTrue(BackupMerge.mergeRemoteNodeSessionMetadata(room, room.copy(isPinned = false)).value.isPinned)
    }

    private fun decodeRow(family: String, pin: JsonElement?, favorite: JsonElement?): AppBackupEnvelope {
        val root = backupJson.parseToJsonElement(loadBackupFixture("full-envelope.json")).jsonObject
        val row = root.getValue(family).jsonArray.single().jsonObject.toMutableMap()
        row.remove("isPinned")
        row.remove("isFavorite")
        pin?.let { row["isPinned"] = it }
        favorite?.let { row["isFavorite"] = it }
        return decodeBackupEnvelope(JsonObject(root + (family to JsonArray(listOf(JsonObject(row))))).toString())
    }

    private fun AppBackupEnvelope.pin(family: String): Boolean = when (family) {
        "contacts" -> contacts.single().isPinned
        "channels" -> channels.single().isPinned
        else -> remoteNodeSessions.single().isPinned
    }

    private val invalidFlags = listOf(
        JsonPrimitive("true"),
        JsonPrimitive("false"),
        JsonPrimitive(1),
        JsonArray(emptyList()),
        JsonObject(emptyMap()),
    )

    private val families = listOf("contacts", "channels", "remoteNodeSessions")
}
