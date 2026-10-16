package one.meshcore.shared.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The backed-up preference set is part of the wire format: a key this type does not declare decodes
 * to nothing and is dropped on re-encode, because [backupJson] ignores unknown keys. Assertions run
 * against the JSON text rather than the properties so they describe the wire contract itself.
 */
class BackupUserDefaultsTest {
    @Test
    fun mapPreferenceKeysSurviveDecodeAndReencode() {
        val root = backupJson.parseToJsonElement(loadBackupFixture("full-envelope.json")).jsonObject
        val userDefaults =
            JsonObject(
                root.getValue("userDefaults").jsonObject +
                    mapOf(
                        "showDiscoveredNodesOnMap" to JsonPrimitive(true),
                        "mapColorSchemePreference" to JsonPrimitive("dark"),
                        "mapClusteringEnabled" to JsonPrimitive(false),
                    ),
            )
        val withKeys = JsonObject(root + mapOf("userDefaults" to userDefaults))
        val source = withKeys.toString()

        val reencoded = encodeBackupEnvelope(decodeBackupEnvelope(source))

        val restored = backupJson.parseToJsonElement(reencoded).jsonObject.getValue("userDefaults").jsonObject
        assertEquals(true, restored["showDiscoveredNodesOnMap"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("dark", restored["mapColorSchemePreference"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, restored["mapClusteringEnabled"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun absentPreferenceKeysStayAbsentOnReencode() {
        val envelope = decodeBackupEnvelope(loadBackupFixture("full-envelope.json"))
        val restored = backupJson.parseToJsonElement(encodeBackupEnvelope(envelope)).jsonObject
            .getValue("userDefaults").jsonObject
        assertFalse("showDiscoveredNodesOnMap" in restored)
        assertFalse("mapColorSchemePreference" in restored)
        assertFalse("mapClusteringEnabled" in restored)
        assertFalse("translationTargetLanguage" in restored)
        assertFalse("useDefaultTranslationApp" in restored)
        assertFalse("translationOffersEnabled" in restored)
    }

    @Test
    fun translationTargetKeysSurviveDecodeAndReencode() {
        val root = backupJson.parseToJsonElement(loadBackupFixture("full-envelope.json")).jsonObject
        val userDefaults =
            JsonObject(
                root.getValue("userDefaults").jsonObject +
                    mapOf(
                        "translationTargetLanguage" to JsonPrimitive("da"),
                        "useDefaultTranslationApp" to JsonPrimitive(true),
                        "translationOffersEnabled" to JsonPrimitive(false),
                    ),
            )
        val withKeys = JsonObject(root + mapOf("userDefaults" to userDefaults))
        val source = withKeys.toString()
        val reencoded = encodeBackupEnvelope(decodeBackupEnvelope(source))
        val restored = backupJson.parseToJsonElement(reencoded).jsonObject.getValue("userDefaults").jsonObject
        assertEquals("da", restored["translationTargetLanguage"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, restored["useDefaultTranslationApp"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, restored["translationOffersEnabled"]?.jsonPrimitive?.booleanOrNull)
    }
}
