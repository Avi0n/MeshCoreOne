package one.meshcore.android

import android.content.pm.ApplicationInfo
import android.content.res.XmlResourceParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class SystemBackupPolicyInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedManifestDisablesBackupAndLinksThePackagedRules() {
        val info = context.applicationInfo
        assertFalse(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        context.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "application") {
                    assertFalse(parser.getAttributeBooleanValue(ANDROID_NAMESPACE, "allowBackup", true))
                    assertEquals(
                        R.xml.backup_rules,
                        parser.getAttributeResourceValue(ANDROID_NAMESPACE, "fullBackupContent", 0),
                    )
                    assertEquals(
                        R.xml.data_extraction_rules,
                        parser.getAttributeResourceValue(ANDROID_NAMESPACE, "dataExtractionRules", 0),
                    )
                    return
                }
            }
            throw AssertionError("Packaged manifest has no application element")
        }
    }

    @Test
    fun legacyRulesExcludeEveryStorageDomain() {
        context.resources.getXml(R.xml.backup_rules).use { parser ->
            assertEquals(setOf("full-backup-content"), readExclusions(parser).keys)
        }
    }

    @Test
    fun modernRulesExcludeCloudAndDeviceTransferIndependently() {
        context.resources.getXml(R.xml.data_extraction_rules).use { parser ->
            assertEquals(setOf("cloud-backup", "device-transfer"), readExclusions(parser).keys)
        }
    }

    private fun readExclusions(parser: XmlResourceParser): Map<String, Set<String>> {
        val sections = mutableMapOf<String, MutableSet<String>>()
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "data-extraction-rules" -> Unit
                "full-backup-content", "cloud-backup", "device-transfer" -> {
                    section = parser.name
                    assertFalse(sections.containsKey(section))
                    sections[section] = mutableSetOf()
                }
                "exclude" -> {
                    assertEquals(".", parser.getAttributeValue(null, "path"))
                    val domain = parser.getAttributeValue(null, "domain")
                    assertTrue("Duplicate or unscoped exclusion $domain", sections[section]?.add(domain) == true)
                }
                else -> throw AssertionError("Unexpected backup rule ${parser.name}")
            }
        }
        sections.values.forEach { domains -> assertEquals(STORAGE_DOMAINS, domains) }
        return sections
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        val STORAGE_DOMAINS = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
    }
}
