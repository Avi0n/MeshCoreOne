package one.meshcore.shared.backup

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Encode half of the cross-platform backup freeze: Kotlin must emit sorted-key compact JSON with the
 * same optionals / base64 / UUID / date conventions as Swift's `makeBackupJSONEncoder()`.
 */
class BackupFixtureEncodeTest {
    @Test
    fun fullEnvelopeRoundTripsStructurallyAndKeepsSortedKeys() {
        val original = loadBackupFixture("full-envelope.json")
        val envelope = decodeBackupEnvelope(original)
        val encoded = encodeBackupEnvelope(envelope)

        assertTrue(jsonKeysAreSorted(backupJson.parseToJsonElement(encoded)), "encode must sort keys")
        assertFalse(encoded.contains(": "), "encode must be compact (no pretty-print spaces)")
        assertFalse(encoded.contains("\n"), "encode must be single-line compact JSON")

        val redecoded = decodeBackupEnvelope(encoded)
        assertEquals(envelope.version, redecoded.version)
        assertEquals(envelope.exportDate, redecoded.exportDate)
        assertEquals(envelope.appVersion, redecoded.appVersion)
        assertEquals(envelope.appBuild, redecoded.appBuild)
        assertEquals(envelope.manifest, redecoded.manifest)
        assertEquals(envelope.devices.size, redecoded.devices.size)
        assertEquals(envelope.contacts.size, redecoded.contacts.size)
        assertEquals(envelope.channels.size, redecoded.channels.size)
        assertEquals(envelope.messages.size, redecoded.messages.size)
        assertEquals(envelope.userDefaults, redecoded.userDefaults)

        val device = redecoded.devices.first()
        assertEquals(envelope.devices.first().radioID, device.radioID)
        assertEquals(envelope.devices.first().nodeName, device.nodeName)
        assertEquals(1, device.connectionMethods.size)

        // Semantic JSON equality after sort (tolerates Double string form differences only if values match).
        assertJsonValuesEqual(backupJson.parseToJsonElement(original), backupJson.parseToJsonElement(encoded))
    }

    @Test
    fun unicodeEdgePreservesEmptyVsMissingNicknameOnReencode() {
        val envelope = decodeBackupEnvelope(loadBackupFixture("unicode-edge.json"))
        val encoded = encodeBackupEnvelope(envelope)
        val redecoded = decodeBackupEnvelope(encoded)

        val emptyNickname = redecoded.contacts.first { it.name == "李明" }
        assertEquals("", emptyNickname.nickname)
        val missingNickname = redecoded.contacts.first { it.name == "Renée" }
        assertEquals(null, missingNickname.nickname)

        // Wire must omit null nickname and emit empty string for the other.
        val contacts = backupJson.parseToJsonElement(encoded).jsonObject.getValue("contacts").jsonArray
        val byName =
            contacts.associate { obj ->
                val o = obj.jsonObject
                o.getValue("name").jsonPrimitive.content to o
            }
        assertTrue("nickname" in byName.getValue("李明"))
        assertEquals("", byName.getValue("李明").getValue("nickname").jsonPrimitive.content)
        assertFalse("nickname" in byName.getValue("Renée"))
    }

    @Test
    fun legacyMinimalEmitsEmptyArraysAndOmitsNullUserDefaults() {
        val envelope = decodeBackupEnvelope(loadBackupFixture("legacy-minimal.json"))
        val encoded = encodeBackupEnvelope(envelope)
        val root = backupJson.parseToJsonElement(encoded).jsonObject

        assertTrue("contacts" in root)
        assertEquals(0, root.getValue("contacts").jsonArray.size)
        assertTrue("messages" in root)
        assertEquals(1, root.getValue("messages").jsonArray.size)
        assertFalse("userDefaults" in root, "nil userDefaults must omit the key")

        // After decode, late-added channel defaults are filled; re-export may include them (current
        // app state), which is correct for live export. Round-trip decode still validates.
        val redecoded = decodeBackupEnvelope(encoded)
        assertTrue(redecoded.manifest.validate(redecoded))
        assertEquals(envelope.messages.first().createdAt, redecoded.messages.first().sortDate)
    }

    @Test
    fun encodedNumbersMatchSwiftDoubleStyle() {
        val encoded = encodeBackupEnvelope(decodeBackupEnvelope(loadBackupFixture("full-envelope.json")))
        val root = backupJson.parseToJsonElement(encoded).jsonObject
        val contact = root.getValue("contacts").jsonArray.first().jsonObject
        val remoteSession = root.getValue("remoteNodeSessions").jsonArray.first().jsonObject

        assertEquals("1700000000", root.getValue("exportDate").jsonPrimitive.content)
        assertEquals("51.5074", contact.getValue("latitude").jsonPrimitive.content)
        assertEquals("-0.1278", contact.getValue("longitude").jsonPrimitive.content)
        assertEquals("0", remoteSession.getValue("latitude").jsonPrimitive.content)
    }

    @Test
    fun slashIsEscapedLikeSwiftJSONEncoder() {
        val envelope = decodeBackupEnvelope(loadBackupFixture("full-envelope.json")).copy(appVersion = "a/b")
        val encoded = encodeBackupEnvelope(envelope)

        assertTrue("\"appVersion\":\"a\\/b\"" in encoded)
        assertEquals("a/b", decodeBackupEnvelope(encoded).appVersion)
    }
}

private fun jsonKeysAreSorted(element: kotlinx.serialization.json.JsonElement): Boolean = when (element) {
    is kotlinx.serialization.json.JsonObject -> {
        val keys = element.keys.toList()
        keys == keys.sorted() && element.values.all(::jsonKeysAreSorted)
    }
    is kotlinx.serialization.json.JsonArray -> element.all(::jsonKeysAreSorted)
    else -> true
}

/**
 * Structural equality for backup JSON trees: object keys compared as sets (order-independent),
 * numbers compared by numeric value, strings/bools by content.
 */
private fun assertJsonValuesEqual(
    expected: kotlinx.serialization.json.JsonElement,
    actual: kotlinx.serialization.json.JsonElement,
    path: String = "$",
) {
    when {
        expected is kotlinx.serialization.json.JsonObject && actual is kotlinx.serialization.json.JsonObject -> {
            assertEquals(expected.keys, actual.keys, "keys at $path")
            for (key in expected.keys) {
                assertJsonValuesEqual(expected.getValue(key), actual.getValue(key), "$path.$key")
            }
        }
        expected is kotlinx.serialization.json.JsonArray && actual is kotlinx.serialization.json.JsonArray -> {
            assertEquals(expected.size, actual.size, "array size at $path")
            expected.indices.forEach { i ->
                assertJsonValuesEqual(expected[i], actual[i], "$path[$i]")
            }
        }
        expected is JsonPrimitive && actual is JsonPrimitive -> {
            val eNum = expected.doubleOrNull
            val aNum = actual.doubleOrNull
            if (eNum != null && aNum != null) {
                assertEquals(eNum, aNum, 1e-9, "number at $path")
            } else if (expected.booleanOrNull != null || actual.booleanOrNull != null) {
                assertEquals(expected.booleanOrNull, actual.booleanOrNull, "bool at $path")
            } else {
                assertEquals(expected.contentOrNull, actual.contentOrNull, "string at $path")
            }
        }
        else -> assertEquals(expected.toString(), actual.toString(), "type mismatch at $path")
    }
}
