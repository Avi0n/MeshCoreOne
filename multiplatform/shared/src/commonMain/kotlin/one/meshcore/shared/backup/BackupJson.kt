package one.meshcore.shared.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Codec for backup envelopes. Decode ignores unknown keys so a newer producer can add fields.
 * Encode matches the Swift freeze in `makeBackupJSONEncoder`: lexicographically sorted keys at every
 * object level, compact JSON (no pretty-print spaces), nil optionals omit their keys, empty arrays
 * and empty strings are emitted, Data as base64, UUIDs uppercase, dates as seconds-since-1970
 * numbers (whole seconds without a trailing `.0`), and `/` escaped as `\/` (Foundation
 * `JSONEncoder` default; kotlinx.serialization does not).
 *
 * kotlinx.serialization does not sort object keys, so encode builds a [JsonElement] tree then
 * rewrites it through [BackupJsonWriter.sorted] before compact stringify.
 */
val backupJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * Decodes a full backup envelope from its uncompressed JSON payload. Every decode failure — malformed
 * JSON, a missing family key, a type mismatch — surfaces as [AppBackupError.InvalidFile], the single
 * failure Swift `parseBackup` funnels its decoder into, so the screen shows the localized "invalid
 * file" copy rather than an English serializer message.
 */
// The serializer text is the InvalidFile detail. Chaining the exception would put that untranslated
// message on a user-facing error. SerializationException is an IllegalArgumentException, so one catch
// covers both.
fun decodeBackupEnvelope(jsonText: String): AppBackupEnvelope = try {
    val tree = backupJson.parseToJsonElement(jsonText)
    backupJson.decodeFromJsonElement(AppBackupEnvelope.serializer(), tree.withLegacyChatPins())
} catch (e: IllegalArgumentException) {
    throw AppBackupError.InvalidFile(backupDecodeDetail(e))
}

private fun backupDecodeDetail(error: IllegalArgumentException): String = error.message ?: DECODE_FAILED_DETAIL

/**
 * Before chat pins were split from radio favorites, contacts, channels, and room sessions wrote only
 * `isFavorite`. A missing `isPinned` inherits that value; an explicit false remains false.
 */
private fun JsonElement.withLegacyChatPins(): JsonElement {
    val root = this as? JsonObject ?: return this
    val families = setOf("contacts", "channels", "remoteNodeSessions")
    return JsonObject(
        root.mapValues { (key, value) ->
            if (key !in families || value !is JsonArray) {
                value
            } else {
                JsonArray(value.map { it.withLegacyChatPin(isContact = key == "contacts") })
            }
        },
    )
}

private fun JsonElement.withLegacyChatPin(isContact: Boolean): JsonElement {
    val row = this as? JsonObject ?: return this
    if (isContact) requireBackupBoolean(row["isFavorite"])
    val explicit = row["isPinned"].takeUnless { it == JsonNull }
    if (explicit != null) {
        requireBackupBoolean(explicit)
        return row
    }
    val legacy = row["isFavorite"].takeUnless { it == JsonNull } ?: JsonPrimitive(false)
    requireBackupBoolean(legacy)
    return JsonObject(row + ("isPinned" to legacy))
}

private fun requireBackupBoolean(value: JsonElement?) {
    require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { DECODE_FAILED_DETAIL }
}

private const val DECODE_FAILED_DETAIL = "decode failed"

/**
 * Encodes a backup envelope to the uncompressed JSON wire form (zlib is transport-only, applied by
 * the export UI / file writer later). Output is compact and key-sorted for byte-stable zlib windows.
 */
fun encodeBackupEnvelope(envelope: AppBackupEnvelope): String {
    val tree = backupJson.encodeToJsonElement(AppBackupEnvelope.serializer(), envelope)
    return BackupJsonWriter.stringify(BackupJsonWriter.sorted(tree))
}

private object BackupJsonWriter {
    private const val CONTROL_CHAR_MAX_CODE = 0x1F
    private const val UNICODE_ESCAPE_WIDTH = 4
    private const val RADIX_HEX = 16
    private const val ESCAPE_CAPACITY_SLACK = 8

    fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject ->
            JsonObject(
                element
                    .mapValues { (_, value) -> sorted(value) }
                    .toList()
                    .sortedBy { it.first }
                    .toMap(LinkedHashMap()),
            )
        is JsonArray -> JsonArray(element.map { sorted(it) })
        is JsonPrimitive -> rewriteNumberPrimitive(element)
    }

    fun stringify(element: JsonElement): String = when (element) {
        is JsonObject ->
            element.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                "\"${escapeString(key)}\":${stringify(value)}"
            }
        is JsonArray ->
            element.joinToString(separator = ",", prefix = "[", postfix = "]") { stringify(it) }
        is JsonPrimitive ->
            if (element.isString) {
                "\"${escapeString(element.content)}\""
            } else {
                element.content
            }
    }

    fun formatNumber(value: Double): String = when {
        !value.isFinite() -> value.toString()
        isWholeLongRangeDouble(value) -> value.toLong().toString()
        else -> value.toString()
    }

    private fun rewriteNumberPrimitive(primitive: JsonPrimitive): JsonPrimitive {
        if (primitive.isString || primitive == JsonNull) {
            return primitive
        }
        val content = primitive.content
        if (content == "true" || content == "false") {
            return primitive
        }
        val asDouble = content.toDoubleOrNull()
        return when {
            asDouble == null -> primitive
            isWholeLongRangeDouble(asDouble) -> JsonPrimitive(asDouble.toLong())
            else -> JsonPrimitive(asDouble)
        }
    }

    private fun isWholeLongRangeDouble(value: Double): Boolean = value.isFinite() &&
        value % 1.0 == 0.0 &&
        value >= Long.MIN_VALUE.toDouble() &&
        value <= Long.MAX_VALUE.toDouble()

    private fun escapeString(value: String): String {
        val sb = StringBuilder(value.length + ESCAPE_CAPACITY_SLACK)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '/' -> sb.append("\\/")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> appendOrdinaryOrControlChar(sb, ch)
            }
        }
        return sb.toString()
    }

    private fun appendOrdinaryOrControlChar(sb: StringBuilder, ch: Char) {
        if (ch.code <= CONTROL_CHAR_MAX_CODE) {
            sb.append("\\u")
            sb.append(ch.code.toString(RADIX_HEX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
        } else {
            sb.append(ch)
        }
    }
}
