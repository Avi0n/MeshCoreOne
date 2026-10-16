package one.meshcore.shared.resources

import java.io.File

actual fun loadComposeStringsXml(folder: String): String {
    val dir = requireNotNull(System.getProperty("mc1.composeResourcesDir")) {
        "mc1.composeResourcesDir system property is not set; the Gradle test task must supply it"
    }
    return File(dir, "$folder/strings.xml").readText()
}
